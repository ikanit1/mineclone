package com.mineclone.world;

import java.io.*;
import java.util.*;

/** Host-owned rules with a bounded versioned save section. */
public record WorldRules(Difficulty difficulty,boolean keepInventory,boolean allowCommands,boolean mobGriefing,boolean naturalRegeneration){
    public static final String SAVE_SECTION="mineclone:rules";
    public static final WorldRules LEGACY=new WorldRules(Difficulty.NORMAL,true,true,true,true);
    public WorldRules{Objects.requireNonNull(difficulty);}
    public WorldRules difficulty(Difficulty d){return new WorldRules(d,keepInventory,allowCommands,mobGriefing,naturalRegeneration);}
    public WorldRules set(String name,boolean value){return switch(name){
        case "keepInventory"->new WorldRules(difficulty,value,allowCommands,mobGriefing,naturalRegeneration);
        case "allowCommands"->new WorldRules(difficulty,keepInventory,value,mobGriefing,naturalRegeneration);
        case "mobGriefing"->new WorldRules(difficulty,keepInventory,allowCommands,value,naturalRegeneration);
        case "naturalRegeneration"->new WorldRules(difficulty,keepInventory,allowCommands,mobGriefing,value);
        default->throw new IllegalArgumentException("unknown rule "+name);};}
    public byte[] encode(){try{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeByte(1);out.writeUTF(difficulty.name());out.writeBoolean(keepInventory);out.writeBoolean(allowCommands);out.writeBoolean(mobGriefing);out.writeBoolean(naturalRegeneration);return bytes.toByteArray();}catch(IOException impossible){throw new AssertionError(impossible);}}
    public static WorldRules decode(byte[] body){if(body==null)return LEGACY;if(body.length>64)throw new IllegalArgumentException("rules section too large");try{var in=new DataInputStream(new ByteArrayInputStream(body));if(in.readUnsignedByte()!=1)throw new IOException("unsupported rules version");var rules=new WorldRules(Difficulty.valueOf(in.readUTF()),in.readBoolean(),in.readBoolean(),in.readBoolean(),in.readBoolean());if(in.available()!=0)throw new IOException("trailing rules bytes");return rules;}catch(IOException|IllegalArgumentException invalid){throw new IllegalArgumentException("invalid world rules",invalid);}}
}
