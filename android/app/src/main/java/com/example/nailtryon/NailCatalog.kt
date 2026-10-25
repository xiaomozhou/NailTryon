package com.example.nailtryon

data class NailStyle(val id:String,val name:String,val category:String,val drawableName:String)

object NailCatalog {
    val categories = listOf("纯色美甲","幻彩美甲","手绘美甲","主题美甲","立体美甲")
    val styles = listOf(
        NailStyle("solid_cherry","车厘子红","纯色美甲","nail_cherry"), NailStyle("solid_blueberry","蓝莓黑","纯色美甲","nail_blueberry"),
        NailStyle("solid_lemon","柠檬黄","纯色美甲","nail_lemon"), NailStyle("solid_pink","皮皮粉","纯色美甲","nail_pink"),
        NailStyle("iridescent_spring","春野霓虹","幻彩美甲","nail_spring"), NailStyle("iridescent_mist","轻雾极光","幻彩美甲","nail_mist"),
        NailStyle("handdrawn_dog","奶芙小狗","手绘美甲","nail_dog"), NailStyle("handdrawn_cat","星闪黑猫","手绘美甲","nail_cat"),
        NailStyle("theme_leopard","辣妹星豹","主题美甲","nail_leopard"), NailStyle("theme_star","青星奶闪","主题美甲","nail_star"),
        NailStyle("3d_ballet","粉锻芭蕾","立体美甲","nail_ballet"), NailStyle("3d_tweed","格纹千金","立体美甲","nail_tweed")
    )
    fun byCategory(category:String)=styles.filter{it.category==category}
}
