package com.mineclone.net;

import com.mineclone.save.PlayerRecord;
import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;
import org.joml.Vector3f;

/**
 * A player checkpoint as the network passes it; world blocks belong to the world's own save.
 *
 * <p>A thin adapter over {@link PlayerRecord}. Since protocol v8 (NET-02) the
 * record itself travels — {@link #write}: {@code bytes PlayerRecordCodec} with
 * its own format version — so equipment, effects, the personal spawn and the
 * sections of a newer build reach the guest. The protocol-v7 layout of the
 * fields below survives as {@link #writeV7}/{@link #readV7}: version-1 guest
 * files are stored in it. The adapter itself goes with M4.
 */
public final class PlayerData {
    private final PlayerRecord record;
    public final ItemStack[] inventory, pending;
    public final float x, y, z, yaw, pitch, health, hunger;
    public final int selected;
    public final byte[] progress;

    public PlayerData(ItemStack[] inventory, ItemStack[] pending, float x, float y, float z,
            float yaw, float pitch, float health, float hunger, int selected, byte[] progress) {
        this(PlayerRecord.builder().inventory(inventory).pending(pending)
                .pose(x, y, z, yaw, pitch, selected)
                .vitals(PlayerRecord.Vitals.legacy(health, hunger))
                .progress(progress).build());
    }

    public PlayerData(PlayerRecord record) {
        this.record = java.util.Objects.requireNonNull(record);
        this.inventory = record.inventory();
        this.pending = record.pending();
        var pose = record.pose();
        this.x = (float) pose.x(); this.y = (float) pose.y(); this.z = (float) pose.z();
        this.yaw = pose.yaw(); this.pitch = pose.pitch(); this.selected = pose.selected();
        this.health = record.vitals().health();
        this.hunger = record.vitals().hunger();
        this.progress = record.progress();
    }

    public PlayerRecord record() { return record; }

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

    /** Protocol v8: the whole record. */
    public void write(PacketBuf b) {
        try {
            b.bytes(com.mineclone.save.PlayerRecordCodec.encode(record));
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public byte[] bytes() { PacketBuf b=new PacketBuf(); write(b); return b.toBytes(); }

    /** @return the checkpoint, or null for a cut, damaged or newer-than-this-build record */
    public static PlayerData read(PacketBuf b) {
        byte[] body = b.readBytes();
        if (b.truncated())
            return null;
        try {
            return new PlayerData(com.mineclone.save.PlayerRecordCodec.decode(body));
        } catch (java.io.IOException | RuntimeException damaged) {
            return null;
        }
    }

    /** The protocol-v7 layout: inventory, cursor stacks, pose, health, hunger, slot, progress. */
    public void writeV7(PacketBuf b) {
        writeItems(b,inventory); writeItems(b,pending);
        b.f32(x).f32(y).f32(z).f32(yaw).f32(pitch).f32(health).f32(hunger)
                .varInt(selected).bytes(progress);
    }

    public byte[] bytesV7() { PacketBuf b=new PacketBuf(); writeV7(b); return b.toBytes(); }

    public static PlayerData readV7(PacketBuf b) {
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
