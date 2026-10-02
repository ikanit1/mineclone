package com.mineclone.sim;

import com.mineclone.world.Chunk;
import com.mineclone.world.entity.*;
import java.io.*;
import java.util.*;

/** Bounded chunk entity section. Decode validates the entire batch before publishing any mob. */
public final class EntityPersistence {
    public static final String SECTION="entities";
    public static final int MAX=1024;
    private EntityPersistence(){}
    public static boolean belongs(Mob m,Chunk c){return Math.floorDiv((int)Math.floor(m.position.x),16)==c.cx&&Math.floorDiv((int)Math.floor(m.position.z),16)==c.cz;}
    public static byte[] encode(List<Mob> mobs){try{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeByte(1);if(mobs.size()>MAX)throw new IOException("too many mobs");out.writeInt(mobs.size());
        for(var m:mobs){if(m.id<=0||m.dead||!m.persistent)throw new IOException("invalid persisted mob");out.writeLong(m.id);out.writeUTF(m.type.name());out.writeFloat(m.position.x);out.writeFloat(m.position.y);out.writeFloat(m.position.z);out.writeFloat(m.yaw);out.writeFloat(m.health);out.writeFloat(m.age);out.writeBoolean(m.persistent);out.writeFloat(m.loveCooldown);out.writeFloat(m.love);}
        return bytes.toByteArray();}catch(IOException invalid){throw new IllegalArgumentException(invalid);}}
    public static List<Mob> decode(byte[] body){if(body==null)return List.of();if(body.length>MAX*128)throw new IllegalArgumentException("entity section too large");try{var in=new DataInputStream(new ByteArrayInputStream(body));if(in.readUnsignedByte()!=1)throw new IOException("unsupported entity schema");int count=in.readInt();if(count<0||count>MAX)throw new IOException("entity count");var result=new ArrayList<Mob>();var ids=new HashSet<Long>();
        for(int i=0;i<count;i++){long id=in.readLong();var type=MobType.valueOf(in.readUTF());float x=in.readFloat(),y=in.readFloat(),z=in.readFloat(),yaw=in.readFloat(),health=in.readFloat(),age=in.readFloat();boolean persistent=in.readBoolean();float cooldown=in.readFloat(),love=in.readFloat();
            if(id<=0||!ids.add(id)||!Float.isFinite(x+y+z+yaw+health+age+cooldown+love)||Math.abs(x)>1e7||Math.abs(y)>1e7||Math.abs(z)>1e7||health<=0||health>1e6||age< -1200||cooldown<0||cooldown>300||love<0||love>30||!persistent)throw new IOException("invalid mob record");
            var m=new Mob(type,x,y,z,new Random(id));m.id=id;m.yaw=yaw;m.health=health;m.age=age;m.persistent=persistent;m.loveCooldown=cooldown;m.love=love;result.add(m);
        }if(in.available()!=0)throw new IOException("trailing entity bytes");return List.copyOf(result);
    }catch(IOException|IllegalArgumentException invalid){throw new IllegalArgumentException("invalid persisted entities",invalid);}}
    public static byte[] snapshot(List<Mob> mobs,Chunk c){return encode(mobs.stream().filter(m->m.persistent&&!m.dead&&belongs(m,c)).toList());}
}
