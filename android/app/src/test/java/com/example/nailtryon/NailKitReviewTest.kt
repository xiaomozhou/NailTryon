package com.example.nailtryon

import android.graphics.*
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=ComposeHostTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NailKitReviewTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var uri:Uri
    private val saved=AtomicReference<File?>()
    @Before fun setup(){
        val file=File(compose.activity.cacheDir,"kit.jpg")
        file.outputStream().use{Bitmap.createBitmap(800,800,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLUE)}.compress(Bitmap.CompressFormat.JPEG,95,it)}
        uri=Uri.fromFile(file)
    }
    private fun waitText(text:String){compose.waitUntil(10000){compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}}
    @Test fun explicitLeftSelectionExportsThatRowOnlyAfterUserConfirmation() {
        val rows=listOf(KitTestFixture.row(),KitTestFixture.row(500))
        compose.setContent{MaterialTheme{NailKitReview(uri,{saved.set(it)},{},analyze={_,_->NailKitAnalysis(rows,KitSelection(1,"左手在第二排",false))})}}
        waitText("左手在第二排")
        assertNull(saved.get())
        compose.onNodeWithTag("accept-nail-kit").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10000){saved.get()!=null}
        val manifest=Json.parseToJsonElement(File(saved.get(),"manifest.json").readText()).jsonObject
        manifest.getValue("pieces").jsonArray.forEach{assertEquals(455,it.jsonObject.getValue("sourceBox").jsonArray[1].jsonPrimitive.int)}
    }
    @Test fun unknownLabelsRequireChoosingOneCompleteRow() {
        val rows=listOf(KitTestFixture.row(),KitTestFixture.row(500))
        compose.setContent{MaterialTheme{NailKitReview(uri,{saved.set(it)},{},analyze={_,_->NailKitAnalysis(rows,KitSelection(null,"请确认左手的一排",true))})}}
        waitText("请确认左手的一排")
        compose.onNodeWithTag("accept-nail-kit").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("选择第2排五片").performScrollTo().performClick()
        compose.onNodeWithTag("accept-nail-kit").performScrollTo().assertIsEnabled()
        assertNull(saved.get())
    }
    @Test fun manualFivePointsArePassedInThumbToLittleOrderAndDoNotStartHandFitting() {
        val received=AtomicReference<List<NailPoint>?>()
        compose.setContent{MaterialTheme{NailKitReview(uri,{saved.set(it)},{},
            analyze={_,_->NailKitAnalysis(emptyList(),KitSelection(null,"未找到完整排组",true))},
            pointed={_,_,points->received.set(points);KitTestFixture.row().regions})}}
        waitText("未找到完整排组")
        compose.onNodeWithText("手动点选五片").performScrollTo().performClick()
        val picker=compose.onNodeWithTag("kit-point-picker")
        for(x in listOf(100f,210f,320f,430f,540f))picker.performScrollTo().performTouchInput{click(Offset(center.x*2*x/800,center.y*.5f))}
        compose.onNodeWithText("提取这五片").performScrollTo().assertIsEnabled().performClick()
        waitText("请确认五片甲片的对应顺序")
        assertEquals(5,received.get()!!.size)
        received.get()!!.forEachIndexed{i,p->assertEquals(100f+i*110,p.x,2f);assertEquals(200f,p.y,2f)}
        compose.onNodeWithTag("accept-nail-kit").performScrollTo().assertIsEnabled()
        assertNull(saved.get())
    }
    @Test fun fingerAssignmentCanBeCorrectedWithoutDuplicatingOrLosingAnInstance() {
        val row=KitTestFixture.row()
        compose.setContent{MaterialTheme{NailKitReview(uri,{saved.set(it)},{},analyze={_,_->NailKitAnalysis(listOf(row),KitSelection(0,"请核对对应关系",false))})}}
        waitText("请核对对应关系")
        compose.onNodeWithText("拇指").performScrollTo().performClick()
        val little=compose.onAllNodesWithText("小指")
        little[little.fetchSemanticsNodes().size-1].performClick()
        compose.onNodeWithTag("accept-nail-kit").performScrollTo().performClick()
        compose.waitUntil(10000){saved.get()!=null}
        val pieces=Json.parseToJsonElement(File(saved.get(),"manifest.json").readText()).jsonObject.getValue("pieces").jsonArray
        assertEquals(row.regions.last().box.left,pieces[0].jsonObject.getValue("sourceBox").jsonArray[0].jsonPrimitive.int)
        assertEquals(5,pieces.map{it.jsonObject.getValue("sourceBox")}.distinct().size)
    }
    @Test fun modelFailureDoesNotEnableUseAndCanReturnWithoutSaving() {
        var returned=false
        compose.setContent{MaterialTheme{NailKitReview(uri,{saved.set(it)},{returned=true},analyze={_,_->error("failed")})}}
        waitText("未能自动提取完整五片，可点选同一排五片美甲")
        compose.onNodeWithTag("accept-nail-kit").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("返回").performScrollTo().performClick()
        assertTrue(returned);assertNull(saved.get())
    }
}
