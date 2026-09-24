package com.mineclone.net;

import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;
import org.joml.Vector3f;

/** A detached player checkpoint; world blocks belong to the world's own save. */
public final class PlayerData {
    private final com.mineclone.save.PlayerRecord record;
    public final ItemStack[] inventory, pending;
    public final float x, y, z, yaw, pitch, health, hunger;
    public final int selected;
    public final byte[] progress;

    public PlayerData(ItemStack[] inventory, ItemStack[] pending, float x, float y, float z,
            float yaw, float pitch, float health, float hunger, int selected, byte[] progress) {
        this(com.mineclone.save.PlayerRecord.builder().inventory(inventory).pending(pending)
                .pose(x,y,z,yaw,pitch,selected).vitals(health,hunger,5,20).progress(progress).build());
    }

    public PlayerData(com.mineclone.save.PlayerRecord record) {
        this.record=java.util.Objects.requireNonNull(record);
        this.inventory=record.inventory(); this.pending=record.pending();
        var pose=record.pose();var vitals=record.vitals();
        this.x=(float)pose.x();this.y=(float)pose.y();this.z=(float)pose.z();this.yaw=pose.yaw();this.pitch=pose.pitch();
        this.health=vitals.health();this.hunger=vitals.hunger();this.selected=pose.selected();this.progress=record.progress();
    }

    public com.mineclone.save.PlayerRecord record() { return record; }

    public static PlayerData empty(Vector3f spawn) {
        return new PlayerData(null,null,spawn.x,spawn.y,spawn.z,0,0,20,20,0,null);
    }

    public PlayerData withItems(ItemStack[] inv, ItemStack[] held) {
        return new PlayerData(record.withItems(inv,held));
    }

    public static ItemStack[] copy(ItemStack[] a) {
        ItemStack[] b=new ItemStack[a.length];
        for(int i=0;i<a.length;i++) b[i]=a[i]==null ? null : a[i].copy();
        return b;
    }

    public void write(PacketBuf b) {
        writeItems(b,inventory); writeItems(b,pending);
        b.f32(x).f32(y).f32(z).f32(yaw).f32(pitch).f32(health).f32(hunger)
                .varInt(selected).bytes(progress);
    }

    public byte[] bytes() { PacketBuf b=new PacketBuf(); write(b); return b.toBytes(); }

    public static PlayerData read(PacketBuf b) {
        ItemStack[] inv=readItems(b,Inventory.SIZE), pending=readItems(b,64);
        float x=b.readF32(),y=b.readF32(),z=b.readF32(),yaw=b.readF32(),pitch=b.readF32();
        float health=b.readF32(),hunger=b.readF32(); int selected=b.readVarInt();
        byte[] progress=b.readBytes();
        if(b.truncated() || inv==null || inv.length!=Inventory.SIZE || pending==null
                || progress.length>65536 || !Float.isFinite(x) || !Float.isFinite(y)
                || !Float.isFinite(z) || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                || !Float.isFinite(health) || !Float.isFinite(hunger) || selected<0 || selected>=9)
            return null;
        return new PlayerData(inv,pending,x,y,z,yaw,pitch,
                Math.max(0,Math.min(20,health)),Math.max(0,Math.min(20,hunger)),selected,progress);
    }

    public static void writeItems(PacketBuf b, ItemStack[] a) {
        b.varInt(a.length); for(ItemStack s:a) Multiplayer.writeStack(b,s);
    }

    public static ItemStack[] readItems(PacketBuf b,int max) {
        int n=b.readVarInt();
        if(n<0 || n>max) { while(b.hasMore())b.readU8(); b.readU8(); return null; }
        ItemStack[] a=new ItemStack[n];
        for(int i=0;i<n;i++) a[i]=Multiplayer.readStack(b);
        return b.truncated() ? null : a;
    }

    public static boolean sameItems(ItemStack[] a,ItemStack[] b) {
        if(a==null || b==null || a.length!=b.length)return false;
        for(int i=0;i<a.length;i++)
            if(a[i]==null ? b[i]!=null : !a[i].contentEquals(b[i]))return false;
        return true;
    }
}
