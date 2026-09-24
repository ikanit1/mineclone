package com.mineclone.save;

import com.mineclone.data.SectionCodec;
import com.mineclone.data.VarInt;
import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;
import java.io.*;
import java.util.*;

/** One section payload for owner and guest storage; network v7 remains a separate adapter. */
public final class PlayerRecordCodec {
    private PlayerRecordCodec() {}
    public static final int MAGIC = 0x50524344; // PRCD
    public static final String LEVEL_MARKER = "player_format";
    public static final Set<String> KNOWN = Set.of("format", "pose", "vitals", "inventory", "equipment",
            "pending", "effects", "spawn", "advancements", "recipes", "progress");
    public static final class TooNewException extends IOException {
        public final int version, minReader;
        public TooNewException(int version, int minReader) { super("player record requires reader " + minReader); this.version=version; this.minReader=minReader; }
    }
    public static byte[] encode(PlayerRecord record) throws IOException { return bytes(out -> write(out, record)); }
    public static PlayerRecord decode(byte[] bytes) throws IOException {
        try (var input = input(bytes)) { PlayerRecord record = read(input); end(input); return record; }
    }
    public static void write(DataOutput out, PlayerRecord r) throws IOException {
        Map<String, byte[]> sections = new LinkedHashMap<>();
        sections.put("format", bytes(o -> { o.writeInt(MAGIC); o.writeInt(PlayerRecord.VERSION); o.writeInt(PlayerRecord.VERSION); }));
        sections.put("pose", bytes(o -> { var p=r.pose(); o.writeDouble(p.x());o.writeDouble(p.y());o.writeDouble(p.z());o.writeFloat(p.yaw());o.writeFloat(p.pitch());o.writeInt(p.selected()); }));
        sections.put("vitals", bytes(o -> { var v=r.vitals();o.writeFloat(v.health());o.writeFloat(v.hunger());o.writeFloat(v.saturation());o.writeFloat(v.air()); }));
        sections.put("inventory", items(r.inventory())); sections.put("equipment", items(r.equipment())); sections.put("pending", items(r.pending()));
        byte[] retained = r.retainedEffects();
        sections.put("effects", retained != null ? retained : bytes(o -> {
            VarInt.write(o, r.effects().size());
            for (var effect:r.effects()) { o.writeUTF(effect.id());o.writeLong(effect.remainingTicks());o.writeInt(effect.amplifier()); }
        }));
        sections.put("spawn", bytes(o -> { var p=r.spawn();o.writeBoolean(p!=null);if(p!=null){o.writeDouble(p.x());o.writeDouble(p.y());o.writeDouble(p.z());} }));
        sections.put("advancements", names(r.advancements())); sections.put("recipes", names(r.recipes())); sections.put("progress", r.progress());
        sections.putAll(r.extraSections()); SectionCodec.write(out, sections);
    }
    public static PlayerRecord read(DataInput in) throws IOException {
        Map<String, byte[]> sections = SectionCodec.read(in);
        try {
            try (var data=required(sections,"format")) {
                if(data.readInt()!=MAGIC)throw new IOException("invalid player record magic");
                int version=data.readInt(), min=data.readInt();
                if(version<1 || min<1 || min>version)throw new IOException("invalid player record version");
                if(min>PlayerRecord.VERSION)throw new TooNewException(version,min);
                end(data);
            }
            var b=PlayerRecord.builder();
            try(var data=required(sections,"pose")) { b.pose(data.readDouble(),data.readDouble(),data.readDouble(),data.readFloat(),data.readFloat(),data.readInt());end(data); }
            try(var data=required(sections,"vitals")) { b.vitals(data.readFloat(),data.readFloat(),data.readFloat(),data.readFloat());end(data); }
            ItemStack[] inventory=readItems(requiredBytes(sections,"inventory"),Inventory.SIZE);
            if(inventory.length!=Inventory.SIZE)throw new IOException("player inventory must have 36 slots");
            b.inventory(inventory);
            b.equipment(sections.containsKey("equipment")?readItems(sections.get("equipment"),64):null);
            b.pending(sections.containsKey("pending")?readItems(sections.get("pending"),PlayerRecord.MAX_PENDING):null);
            if(sections.containsKey("spawn"))try(var data=input(sections.get("spawn"))) {
                int present=data.readUnsignedByte();if(present>1)throw new IOException("invalid personal spawn");
                if(present==1)b.spawn(new PlayerRecord.Spawn(data.readDouble(),data.readDouble(),data.readDouble()));end(data);
            }
            if(sections.containsKey("advancements"))b.advancements(readNames(sections.get("advancements")));
            if(sections.containsKey("recipes"))b.recipes(readNames(sections.get("recipes")));
            b.progress(sections.get("progress"));
            if(sections.containsKey("effects")) {
                byte[] effects=sections.get("effects");
                try(var data=input(effects)) {
                    int count=bounded(VarInt.read(data),1024);List<PlayerRecord.Effect> parsed=new ArrayList<>();
                    for(int i=0;i<count;i++)parsed.add(new PlayerRecord.Effect(data.readUTF(),data.readLong(),data.readInt()));
                    end(data);b.effects(parsed);
                } catch(IOException | IllegalArgumentException corrupt) {
                    // Effects are optional presentation/simulation state. Keep evidence byte-for-byte;
                    // inventory/equipment/pending parsing above must still succeed independently.
                    b.damagedEffects(effects,"effects section retained but not applied: " + corrupt.getMessage());
                }
            }
            sections.forEach((key,value)->{if(!KNOWN.contains(key))b.section(key,value);});
            return b.build();
        } catch(IllegalArgumentException invalid) { throw new IOException("invalid player record",invalid); }
    }
    private static byte[] items(ItemStack[] items) throws IOException { return bytes(o->{VarInt.write(o,items.length);for(var item:items)ItemStackCodec.write(o,item);}); }
    private static ItemStack[] readItems(byte[] bytes,int limit) throws IOException {
        try(var input=input(bytes)) {
            int count=bounded(VarInt.read(input),limit);ItemStack[] result=new ItemStack[count];
            for(int i=0;i<count;i++)result[i]=ItemStackCodec.read(input);end(input);return result;
        }
    }
    private static byte[] names(List<String> names) throws IOException { return bytes(o->{VarInt.write(o,names.size());for(String name:names)o.writeUTF(name);}); }
    private static List<String> readNames(byte[] bytes) throws IOException {
        try(var input=input(bytes)) { int count=bounded(VarInt.read(input),4096);List<String> result=new ArrayList<>();for(int i=0;i<count;i++)result.add(input.readUTF());end(input);return result; }
    }
    private static int bounded(int count,int max) throws IOException { if(count<0||count>max)throw new IOException("invalid player section count");return count; }
    private static byte[] requiredBytes(Map<String,byte[]> sections,String key)throws IOException {
        byte[] bytes=sections.get(key);if(bytes==null)throw new IOException("missing player section "+key);return bytes;
    }
    private static DataInputStream required(Map<String,byte[]> sections,String key)throws IOException{return input(requiredBytes(sections,key));}
    private static DataInputStream input(byte[] bytes){return new DataInputStream(new ByteArrayInputStream(bytes));}
    private static void end(DataInputStream input)throws IOException{if(input.read()!=-1)throw new IOException("trailing player section bytes");}
    @FunctionalInterface private interface Writer{void write(DataOutputStream out)throws IOException;}
    private static byte[] bytes(Writer writer)throws IOException{var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){writer.write(out);}return bytes.toByteArray();}
}
