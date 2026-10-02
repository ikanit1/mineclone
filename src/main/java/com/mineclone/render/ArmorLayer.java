package com.mineclone.render;

import com.mineclone.core.AppPaths;
import com.mineclone.world.Equipment;
import com.mineclone.world.ItemStack;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import java.io.File;
import static org.lwjgl.opengl.GL11.*;

/** Four authored sheets, shared by body color/shadow passes and the first-person sleeve. */
public final class ArmorLayer implements AutoCloseable {
    private final Map<String,Integer> textures=new HashMap<>();
    private final Map<com.mineclone.item.Item,Integer> itemTextures=new java.util.IdentityHashMap<>();
    public int texture(ItemStack stack){
        if(stack==null||stack.item.armor==null)return 0;
        Integer cached=itemTextures.get(stack.item);if(cached!=null)return cached;
        String id=stack.item.id.path(),material=id.substring(0,id.indexOf('_'));
        int texture=textures.computeIfAbsent(material,m->{try{
            var raw=ImageIO.read(AppPaths.file("assets/mobs/armor/"+m+".png"));
            if(raw==null)throw new IllegalStateException("invalid armor sheet "+m);
            return MobRenderer.uploadTexture(MobSkins.normalize(raw));
        }catch(java.io.IOException e){throw new IllegalStateException("cannot read armor sheet "+m,e);}});
        itemTextures.put(stack.item,texture);return texture;
    }
    /** Body index to slot; boots are drawn separately on the leg ends. */
    public static int slot(int part){return part==1?Equipment.HEAD:part==0||part>=4?Equipment.CHEST:Equipment.LEGS;}
    public void close(){for(int texture:textures.values())glDeleteTextures(texture);textures.clear();itemTextures.clear();}
}
