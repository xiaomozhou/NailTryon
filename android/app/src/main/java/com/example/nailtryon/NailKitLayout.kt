package com.example.nailtryon

import kotlin.math.*

internal enum class NailFinger(val title:String) { THUMB("拇指"),INDEX("食指"),MIDDLE("中指"),RING("无名指"),LITTLE("小指") }
internal enum class KitHand { LEFT,RIGHT }
internal data class KitText(val text:String,val center:NailPoint)
internal data class KitRow(val regions:List<NailRegion>) {
    val center get()=NailPoint(regions.map{it.center.x}.average().toFloat(),regions.map{it.center.y}.average().toFloat())
}
internal data class KitSelection(val rowIndex:Int?,val reason:String,val needsConfirmation:Boolean)

internal object NailKitLayout {
    /** RANSAC over centers handles tilted cards; no fixed upper/lower image crop. */
    fun rows(regions:List<NailRegion>):List<KitRow> {
        if(regions.size<5)return emptyList()
        val remaining=regions.toMutableList();val result=mutableListOf<KitRow>()
        while(remaining.size>=5) {
            var best:List<NailRegion>?=null;var bestScore=Float.NEGATIVE_INFINITY
            for(i in remaining.indices) for(j in i+1 until remaining.size) {
                val a=remaining[i].center;val b=remaining[j].center
                val length=hypot(b.x-a.x,b.y-a.y);if(length<1)continue
                var ux=(b.x-a.x)/length;var uy=(b.y-a.y)/length
                if(ux<0 || (abs(ux)<.01f && uy<0)){ux=-ux;uy=-uy}
                val width=remaining.map{min(it.box.width,it.box.height).toFloat()}.sorted()[remaining.size/2]
                val near=remaining.filter{abs((it.center.x-a.x)*uy-(it.center.y-a.y)*ux)<width*.4f}
                    .sortedBy{it.center.x*ux+it.center.y*uy}
                for(group in near.windowed(5)) {
                    val positions=group.map{it.center.x*ux+it.center.y*uy}
                    val gaps=positions.zipWithNext().map{it.second-it.first}
                    // A double-width gap often means a missing nail followed by a background object.
                    if(gaps.min()<width*.45f || gaps.max()>gaps.min()*1.8f)continue
                    val residual=group.sumOf{abs((it.center.x-a.x)*uy-(it.center.y-a.y)*ux).toDouble()}.toFloat()/width
                    val score=group.sumOf{it.confidence.toDouble()}.toFloat()-residual*.15f
                    if(score>bestScore){bestScore=score;best=group}
                }
            }
            if(best==null)break
            result.add(KitRow(best));remaining.removeAll(best.toSet())
        }
        return result.sortedWith(compareBy<KitRow>{it.center.y}.thenBy{it.center.x})
    }

    fun choose(rows:List<KitRow>,text:List<KitText>,ocrSucceeded:Boolean):KitSelection {
        if(rows.isEmpty())return KitSelection(null,"未能找到完整的一排五片美甲",true)
        if(!ocrSucceeded)return KitSelection(null,"未能确认左右手标记，请选择左手的一排；无标记可任选一排",true)
        val singlePair=text.any{it.text.trim().equals("L",true)} && text.any{it.text.trim().equals("R",true)}
        val labels=text.mapNotNull { t ->
            val word=t.text.trim().lowercase().replace(Regex("[\\s:：()（）]"),"")
            val hand=when(word){"left","左手","左"->KitHand.LEFT;"right","右手","右"->KitHand.RIGHT;"l"->if(singlePair)KitHand.LEFT else null;"r"->if(singlePair)KitHand.RIGHT else null;else->null}
            hand?.let{it to t.center}
        }
        val assignments=labels.mapNotNull { (hand,point) ->
            val distances=rows.mapIndexed { i,row ->
                val a=row.regions.first().center;val b=row.regions.last().center
                val len=hypot(b.x-a.x,b.y-a.y).coerceAtLeast(1f);val ux=(b.x-a.x)/len;val uy=(b.y-a.y)/len
                val along=(point.x-a.x)*ux+(point.y-a.y)*uy
                val normal=abs((point.x-a.x)*uy-(point.y-a.y)*ux)
                val nailLength=row.regions.map{max(it.box.width,it.box.height)}.average().toFloat()
                i to if(along in -nailLength*2..(len+nailLength*2) && normal<nailLength*.85f) normal else Float.POSITIVE_INFINITY
            }.sortedBy{it.second}
            if(!distances[0].second.isFinite() || (distances.size>1 && distances[1].second-distances[0].second<8)) null else Triple(hand,distances[0].first,point)
        }
        val left=assignments.filter{it.first==KitHand.LEFT}.map{it.second}.distinct()
        val right=assignments.filter{it.first==KitHand.RIGHT}.map{it.second}.distinct()
        if(left.size==1 && left.single() !in right)return KitSelection(left.single(),"已选择标记为左手的一排",false)
        if(labels.isNotEmpty())return KitSelection(null,"左右手标记不完整或有冲突，请确认左手的一排",true)
        return KitSelection(0,"未识别到左右手标记，已选择第一排五片",false)
    }

    /** Standard five-slot order, oriented from the wider endpoint (thumb). Never size-sort the middle three. */
    fun fingerOrder(row:KitRow):List<NailRegion>? {
        if(row.regions.size!=5)return null
        fun width(n:NailRegion):Float {
            val c=n.orientedCorners
            return if(c.size==4)min(hypot(c[1].x-c[0].x,c[1].y-c[0].y),hypot(c[2].x-c[1].x,c[2].y-c[1].y)) else min(n.box.width,n.box.height).toFloat()
        }
        val first=width(row.regions.first());val last=width(row.regions.last())
        if(max(first,last)<min(first,last)*1.15f)return null
        return if(first>last)row.regions else row.regions.reversed()
    }
}
