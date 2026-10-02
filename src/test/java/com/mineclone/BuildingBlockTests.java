package com.mineclone;

import com.mineclone.world.*;
import com.mineclone.world.shape.*;
import com.mineclone.world.behavior.*;
import com.mineclone.world.entity.PathFinder;
import com.mineclone.save.*;
import java.nio.file.*;
import java.util.*;

final class BuildingBlockTests {
    static void runAll(TestMain.Runner r) {
        r.run("building slabs occupy the selected half and combine without hiding adjacent exposed faces", BuildingBlockTests::slabs);
        r.run("fence connections follow neighbours without persisted meta and block a jump-height body", BuildingBlockTests::fences);
        r.run("gates and trapdoors toggle through shared behaviour; A star respects the gate state", BuildingBlockTests::gates);
        r.run("ladders require their clicked wall and collapse when it is removed", BuildingBlockTests::ladders);
        r.run("all appended building blocks and metadata survive a real chunk checkpoint", BuildingBlockTests::saving);
        r.run("a double slab drops two units and a ladder catches a damaging fall", BuildingBlockTests::conservation);
    }
    static final BlockType[] BUILDING={BlockType.PLANK_SLAB,BlockType.COBBLE_SLAB,BlockType.STONE_SLAB,BlockType.FENCE,BlockType.FENCE_GATE,BlockType.LADDER,BlockType.TRAPDOOR};
    static World blank() { World w = new World(88); w.publish(new Chunk(0,0)); return w; }
    static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }
    static void conservation(){
        var drops=com.mineclone.item.Items.get().loot().blockDrops(BlockType.PLANK_SLAB,
                new com.mineclone.item.loot.LootContext(1,4,50,4,null,true,GameMode.SURVIVAL,null,2));
        check(drops.size()==1&&drops.get(0).count==2,"double slab lost a unit");
        World w=blank();w.setBlock(5,50,5,BlockType.STONE);
        for(int y=51;y<70;y++)w.setBlock(5,y,5,BlockType.LADDER,(byte)1);
        var player=new com.mineclone.game.Player();player.respawn(5.5f,67,5.5f);player.velocity.y=-40;player.fallDistance=30;
        player.update(.05f,w,null,false);check(player.health==20&&player.fallDistance==0&&player.velocity.y>-.2f,"ladder failed to catch fall");
        w.setBlock(8,50,5,BlockType.FENCE);player.respawn(8.5f,60,5.5f);
        for(int i=0;i<600&&!player.onGround;i++)player.update(1f/120,w,null,false);
        check(player.onGround&&Math.abs(player.position.y-51.5f)<.01f,"fence top ignored");
    }
    private static void slabs() {
        float[] boxes = BlockShape.buffer();
        Shapes.SLAB.collision((byte)0,boxes); check(boxes[1]==0 && boxes[4]==.5f,"bottom slab");
        Shapes.SLAB.collision((byte)1,boxes); check(boxes[1]==.5f && boxes[4]==1,"top slab");
        check(Shapes.SLAB.fullCube((byte)2),"double slab");
        var placement = Behaviors.of(BlockType.PLANK_SLAB);
        check(placement.placementMeta(new PlaceContext(0,-1,0,0,1,-1))==1,"ceiling placement");
        World w = blank(); w.setBlock(4,50,4,BlockType.PLANK_SLAB,(byte)0);
        w.setBlock(5,50,4,BlockType.PLANK_SLAB,(byte)1);
        var mesh = new ChunkMesher(w).buildData(w.getChunk(0,0))[0];
        check(mesh.indices.length==72,"opposite halves hid an exposed face: "+mesh.indices.length);
        w.setBlock(5,50,4,BlockType.PLANK_SLAB,(byte)0);
        check(new ChunkMesher(w).buildData(w.getChunk(0,0))[0].indices.length==60,"same halves retained internal face");
    }
    private static void fences() {
        World w=blank(); w.setBlock(5,50,5,BlockType.FENCE); float[] b=BlockShape.buffer();
        check(Shapes.collision(w,5,50,5,b)==1 && b[4]==1.5f,"isolated fence");
        w.setBlock(6,50,5,BlockType.FENCE); check(Shapes.collision(w,5,50,5,b)==2,"fence neighbour not connected");
        w.setBlock(5,50,6,BlockType.STONE); check(Shapes.collision(w,5,50,5,b)==3,"full block not connected");
        w.setBlock(6,50,5,BlockType.AIR); check(Shapes.collision(w,5,50,5,b)==2,"removed connection persisted");
        check(w.getBlockMeta(5,50,5)==0,"connection leaked into saved meta");
        check(!PathFinder.passable(w,5,51,5),"A star can jump over 1.5-high fence");
    }
    private static void gates() {
        World w=blank(); w.setBlock(5,49,5,BlockType.STONE); w.setBlock(5,50,5,BlockType.FENCE_GATE);
        check(!PathFinder.passable(w,5,50,5),"closed gate traversable");
        var gate=Behaviors.of(BlockType.FENCE_GATE);
        check(gate.use(w,5,50,5,(byte)0,new UseContext(1))==UseResult.CONSUMED,"gate use");
        check(PathFinder.passable(w,5,50,5),"open gate blocks A star");
        float[] b=BlockShape.buffer(); check(Shapes.collision(w,5,50,5,b)==0,"open gate collision");
        w.setBlock(6,50,5,BlockType.TRAPDOOR,(byte)8);
        Shapes.collision(w,6,50,5,b); check(b[1]==1-Shapes.DOOR_THICKNESS,"top trapdoor");
        Behaviors.of(BlockType.TRAPDOOR).use(w,6,50,5,(byte)8,new UseContext(1));
        Shapes.collision(w,6,50,5,b); check(b[4]==1 && (w.getBlockMeta(6,50,5)&4)!=0,"trapdoor swing");
    }
    private static void ladders() {
        World w=blank(); var ladder=Behaviors.of(BlockType.LADDER); var ctx=new PlaceContext(1,0,0,0,1,-1);
        check(!ladder.canPlaceAt(w,5,50,5,ctx),"unsupported ladder placed");
        w.setBlock(4,50,5,BlockType.STONE); check(ladder.canPlaceAt(w,5,50,5,ctx),"valid wall refused");
        byte meta=ladder.placementMeta(ctx); w.setBlock(5,50,5,BlockType.LADDER,meta);
        check(ladder.canSurvive(w,5,50,5,meta),"ladder fell from valid wall");
        var drops=new ArrayList<ItemStack>(); w.simulate((s,x,y,z)->drops.add(s),0);
        w.setBlock(4,50,5,BlockType.AIR); w.processNeighbourUpdates(256);
        check(w.getBlock(5,50,5)==BlockType.AIR && drops.size()==1,"ladder did not collapse once");
    }
    private static void saving() throws Exception {
        Path root=Files.createTempDirectory("building-save-"); var save=new SaveManager(root.toFile());
        try {
            World w=blank(); int x=1;
            for(BlockType type:BUILDING)
                w.setBlock(x++,50,5,type,(byte)9);
            Chunk source=w.getChunk(0,0);
            save.saveChunkAsync("w",new ChunkSnapshot(0,0,source.copyBlocks(),source.copyMeta())); save.flushAndAwait();
            Chunk restored=new Chunk(0,0); var snap=save.loadChunk("w",0,0);check(snap!=null,"chunk restore");
            restored.restore(snap.blocks,snap.meta);
            x=1; for(BlockType type:BUILDING) {
                check(restored.get(x,50,5)==type && restored.getMeta(x,50,5)==9,"lost "+type); x++;
            }
        } finally { save.flushAndAwait(); try(var f=Files.walk(root)){for(Path p:f.sorted(Comparator.reverseOrder()).toList())Files.delete(p);} }
    }
}
