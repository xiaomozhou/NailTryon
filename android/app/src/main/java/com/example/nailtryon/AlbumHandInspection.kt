package com.example.nailtryon

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

internal enum class HandPhotoProblem(val message:String) {
    NO_HAND("未能确认完整手部，请拍摄清晰的左手手背"),
    MULTIPLE_HANDS("请只保留一只左手"),
    LEFT_REQUIRED("请使用左手照片"),
    BACK_REQUIRED("请将左手手背朝向镜头，避免手心或侧手照片"),
    FINGERS_REQUIRED("请伸直并自然张开五指，避免并拢或交叉"),
    CLIPPED("请让全部手指完整入镜"),
    NAILS_REQUIRED("请露出全部五片指甲，不要遮挡指甲"),
}

internal data class AlbumHandCheck(val problems:List<HandPhotoProblem>,val nails:NailDetection?=null) {
    val accepted get() = problems.isEmpty() && nails?.readyForAlignment==true
}

internal suspend fun inspectAlbumHand(context:Context,photo:Bitmap):AlbumHandCheck=withContext(Dispatchers.Default) {
    fun model(name:String)=context.assets.open("models/$name").use{it.readBytes()}
    AlbumHandPolicy.evaluate(photo.width,photo.height,
        detectHands={HandLandmarkDetector(model("palm_detection_full_Nx3x192x192.onnx"),model("hand_landmark_full_Nx3x224x224.onnx")).use{it.detect(photo)}},
        detectNails={NailDetector(model("nail-seg.onnx")).use{it.detect(photo)}})
}

internal object AlbumHandPolicy {
    fun evaluate(width:Int,height:Int,detectHands:()->List<HandObservation>,detectNails:()->NailDetection):AlbumHandCheck {
        val hands=detectHands()
        val problems=poseProblems(hands,width,height)
        return if(problems.isNotEmpty()) AlbumHandCheck(problems) else inspect(hands,width,height,detectNails())
    }
    private fun distance(a:HandJoint,b:HandJoint)=hypot(a.x-b.x,a.y-b.y)
    private fun cosine(ax:Float,ay:Float,bx:Float,by:Float)=
        (ax*bx+ay*by)/(hypot(ax,ay)*hypot(bx,by)).coerceAtLeast(1e-6f)

    fun poseProblems(hands:List<HandObservation>,width:Int,height:Int):List<HandPhotoProblem> {
        if(hands.isEmpty()) return listOf(HandPhotoProblem.NO_HAND)
        if(hands.size!=1) return listOf(HandPhotoProblem.MULTIPLE_HANDS)
        val hand=hands.single();val p=hand.points
        if(p.size!=21 || hand.presence<.8f || !hand.presence.isFinite() || !hand.mirroredLeftProbability.isFinite() ||
            p.any{!it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite()}) return listOf(HandPhotoProblem.NO_HAND)
        val problems=mutableListOf<HandPhotoProblem>()
        // Export reports MediaPipe's mirrored/selfie convention. Album pixels are not mirrored here:
        // low MP-Left probability = anatomical left in the displayed, unmirrored photograph.
        if(hand.mirroredLeftProbability>.2f || hand.mirroredLeftProbability<0f) problems.add(HandPhotoProblem.LEFT_REQUIRED)
        val a=p[5];val b=p[17];val wrist=p[0]
        val ax=a.x-wrist.x;val ay=a.y-wrist.y;val bx=b.x-wrist.x;val by=b.y-wrist.y
        val cross=(ax*by-ay*bx)/(hypot(ax,ay)*hypot(bx,by)).coerceAtLeast(1e-6f)
        if(cross>=-.2f) problems.add(HandPhotoProblem.BACK_REQUIRED)
        if(p.drop(1).any{it.x<width*.01f || it.y<height*.01f || it.x>width*.99f || it.y>height*.99f}) problems.add(HandPhotoProblem.CLIPPED)
        val palmWidth=distance(a,b)
        var extended=palmWidth>=min(width,height)*.08f
        for(base in listOf(5,9,13,17)) {
            val mcp=p[base];val pip=p[base+1];val tip=p[base+3]
            if(cosine(pip.x-mcp.x,pip.y-mcp.y,tip.x-pip.x,tip.y-pip.y)<.65f ||
                distance(tip,wrist)<distance(pip,wrist)*1.08f || distance(tip,pip)<distance(mcp,pip)*.65f) extended=false
        }
        if(cosine(p[3].x-p[2].x,p[3].y-p[2].y,p[4].x-p[3].x,p[4].y-p[3].y)<.4f) extended=false
        for((first,second) in listOf(4 to 8,8 to 12,12 to 16,16 to 20)) {
            val baseA=if(first==4)2 else first-3;val baseB=second-3
            // Parallel fingers can still be well separated. Measure physical spacing, not just angle.
            val proximal=(distance(p[baseA],p[baseA+1])+distance(p[baseB],p[baseB+1]))/2
            val minimumGap=max(palmWidth*(if(first==4) .4f else .25f),proximal*.45f+palmWidth*.08f)
            if(distance(p[first],p[second])<minimumGap) extended=false
            if(first!=4) {
                val order=((p[first].x-p[second].x)*(a.x-b.x)+(p[first].y-p[second].y)*(a.y-b.y))/palmWidth.coerceAtLeast(1f)
                if(order<palmWidth*.15f) extended=false
            }
        }
        if(!extended) problems.add(HandPhotoProblem.FINGERS_REQUIRED)
        return problems.distinct()
    }

    fun inspect(hands:List<HandObservation>,width:Int,height:Int,nails:NailDetection):AlbumHandCheck {
        val pose=poseProblems(hands,width,height)
        if(pose.isNotEmpty()) return AlbumHandCheck(pose)
        if(nails.width!=width || nails.height!=height || !nails.readyForAlignment) return AlbumHandCheck(listOf(HandPhotoProblem.NAILS_REQUIRED),nails)
        val p=hands.single().points;val used=BooleanArray(5)
        // One mask per distal finger. Five masks elsewhere (e.g. a nail sample board) cannot pass.
        fun assign(finger:Int):Boolean {
            if(finger==5)return true
            val tip=p[listOf(4,8,12,16,20)[finger]];val dip=p[listOf(3,7,11,15,19)[finger]]
            val dx=tip.x-dip.x;val dy=tip.y-dip.y;val length=hypot(dx,dy)
            if(length<1f)return false
            nails.regions.forEachIndexed { i,nail ->
                val x=nail.center.x-dip.x;val y=nail.center.y-dip.y
                val along=(x*dx+y*dy)/(length*length)
                val across=abs(x*dy-y*dx)/length
                if(!used[i] && along in -.3f..1.3f && across<=length*.6f) {
                    used[i]=true;if(assign(finger+1))return true;used[i]=false
                }
            }
            return false
        }
        return if(assign(0)) AlbumHandCheck(emptyList(),nails) else AlbumHandCheck(listOf(HandPhotoProblem.NAILS_REQUIRED),nails)
    }
}
