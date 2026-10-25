package com.example.nailtryon

data class NailPlacement(val left:Float,val top:Float,val width:Float,val height:Float)
object FusionGeometry { fun placements(width:Float,height:Float):List<NailPlacement>{val nw=width*.13f;val nh=height*.10f;val gap=width*.025f;val start=(width-(5*nw+4*gap))/2f;val y=height*.62f;return (0 until 5).map{ NailPlacement(start+it*(nw+gap),y,nw,nh)}}}
