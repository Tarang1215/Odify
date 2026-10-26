package com.odify.nativeapp

import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap

/** One cached, preblurred scene; resize the glass boundary without stretching its scenery. */
@RequiresApi(33)
internal class OdifySnapshotLens(frame:OdifyModalSnapshot) {
    val shader=RuntimeShader(SOURCE).apply {
        setInputShader("sharpScene",BitmapShader(frame.image.asAndroidBitmap(),Shader.TileMode.CLAMP,Shader.TileMode.CLAMP))
        setInputShader("softScene",BitmapShader(frame.frost.asAndroidBitmap(),Shader.TileMode.CLAMP,Shader.TileMode.CLAMP))
        setFloatUniform("sceneScale",frame.image.width.toFloat()/frame.size.width)
    }
    private val sceneOrigin=frame.origin
    fun update(origin:Offset,width:Float,height:Float,corner:Float,density:Float,transparency:Float) {
        shader.setFloatUniform("sceneOffset",origin.x-sceneOrigin.x,origin.y-sceneOrigin.y)
        shader.setFloatUniform("bounds",width,height)
        shader.setFloatUniform("corner",corner.coerceAtMost(minOf(width,height)/2))
        shader.setFloatUniform("depth",12*density)
        shader.setFloatUniform("strength",10*density)
        shader.setFloatUniform("shade",(.035f+1.08f*(1-transparency)*(1-transparency)).coerceAtMost(.88f))
    }
    companion object {
        private const val SOURCE="""
            uniform shader sharpScene;
            uniform shader softScene;
            uniform float2 sceneOffset;
            uniform float sceneScale;
            uniform float2 bounds;
            uniform float corner;
            uniform float depth;
            uniform float strength;
            uniform float shade;
            half4 main(float2 p) {
                float2 delta=p-bounds*.5;
                float2 q=abs(delta)-bounds*.5+corner;
                float2 outside=max(q,0.0);
                float len=length(outside);
                float distance=len+min(max(q.x,q.y),0.0)-corner;
                float inward=max(-distance,0.0);
                float2 uv=(sceneOffset+p)*sceneScale;
                half3 color=softScene.eval(uv).rgb;
                if(inward<depth*1.8) {
                    float2 n=len>0.0?outside/max(len,.001):(q.x>q.y?float2(1,0):float2(0,1));
                    n*=sign(delta);
                    float rim=clamp(1.0-inward/depth,0.0,1.0);
                    float angle=asin(min(rim*rim,.999));
                    float bend=clamp(tan(angle-asin(sin(angle)/1.45)),0.0,1.3)*strength;
                    float2 bent=uv-n*bend*sceneScale;
                    color=softScene.eval(bent).rgb;
                    color=mix(color,sharpScene.eval(bent).rgb,.78*pow(rim,1.4));
                    float facing=dot(n,float2(-.65,-.76));
                    float glare=pow(max(facing,0.0),3.0)*.27+pow(max(-facing,0.0),4.0)*.09;
                    float light=pow(clamp(1.0-inward/(depth*1.8),0.0,1.0),5.0)*.12+rim*rim*glare;
                    color=mix(color,half3(.941,.969,1),light);
                }
                color*=1.0-shade;
                color=mix(color,half3(1),.025+.02*(1.0-p.y/bounds.y));
                return half4(color,1);
            }
        """
    }
}
