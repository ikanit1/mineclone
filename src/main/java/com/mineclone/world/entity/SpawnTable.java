package com.mineclone.world.entity;

import com.mineclone.data.*;
import com.mineclone.world.*;
import java.nio.file.Path;
import java.util.*;

/** Strict biome-weighted spawn rules shared by initial herds and ongoing wildlife/hostile attempts. */
public final class SpawnTable {
    public record Entry(MobType type,int weight,int min,int max){}
    public record Rule(float herdChance,Map<String,List<Entry>> groups){}
    private final Map<Biome,Rule> biomes;
    private SpawnTable(Map<Biome,Rule> rules){biomes=Map.copyOf(rules);}
    private static final class Default {static final SpawnTable INSTANCE=load(new DataPack(Path.of("assets/data")));}
    public static SpawnTable get(){return Default.INSTANCE;}
    public static SpawnTable load(DataPack pack){var files=pack.files("spawns");if(files.size()!=1)throw new IllegalArgumentException("one overworld spawn table required");return parse(files.get(0).json());}
    public static SpawnTable parse(JsonObject root){root.allowOnly("biomes");var rules=new EnumMap<Biome,Rule>(Biome.class);var raw=root.object("biomes");
        for(String name:raw.keys()){Biome biome;try{biome=Biome.valueOf(name);}catch(IllegalArgumentException e){throw raw.error(name,"unknown biome");}var value=raw.object(name);value.allowOnly("herd_chance","livestock","wildlife","hostile_surface","hostile_cave");float chance=value.number("herd_chance");if(!Float.isFinite(chance)||chance<0||chance>1)throw value.error("herd_chance","0..1 required");var groups=new HashMap<String,List<Entry>>();
            for(String kind:List.of("livestock","wildlife","hostile_surface","hostile_cave")){var entries=new ArrayList<Entry>();int sum=0;for(int i=0;i<value.array(kind).size();i++){var e=value.element(kind,i);e.allowOnly("type","weight","min","max");MobType type;try{type=MobType.valueOf(e.string("type"));}catch(IllegalArgumentException invalid){throw e.error("type","unknown mob");}int weight=e.integer("weight"),min=e.integer("min"),max=e.integer("max");if(weight<=0||weight>10000||min<1||max<min||max>4||entries.size()>=32)throw e.error("weight","bounded weight/group required");if(kind.startsWith("hostile")!=type.hostile||kind.equals("livestock")&&type.temper!=MobType.Temper.PASSIVE)throw e.error("type","mob does not belong to group");sum+=weight;entries.add(new Entry(type,weight,min,max));}if(sum>100000)throw value.error(kind,"weight sum too large");groups.put(kind,List.copyOf(entries));}
            rules.put(biome,new Rule(chance,Map.copyOf(groups)));}if(rules.size()!=Biome.values().length)throw root.error("biomes","all biomes required");return new SpawnTable(rules);}
    public Rule rule(Biome biome){return biomes.get(biome);}
    public Entry choose(Biome biome,String group,Random random){var entries=rule(biome).groups.get(group);if(entries==null||entries.isEmpty())return null;int n=random.nextInt(entries.stream().mapToInt(Entry::weight).sum());for(var entry:entries){n-=entry.weight;if(n<0)return entry;}throw new AssertionError();}
    public List<Mob> initialHerd(World world,Chunk chunk){var random=new Random(world.seed^((long)chunk.cx*0x9e3779b97f4a7c15L)^((long)chunk.cz*0xc2b2ae3d27d4eb4fL));int x=chunk.cx*16+4+random.nextInt(8),z=chunk.cz*16+4+random.nextInt(8);Biome biome=world.biomes.biomeAt(x,z);if(random.nextFloat()>=rule(biome).herdChance)return List.of();var entry=choose(biome,"livestock",random);if(entry==null)return List.of();var herd=new ArrayList<Mob>();int count=entry.min+random.nextInt(entry.max-entry.min+1);
        for(int i=0;i<count;i++){int px=x+i%2,pz=z+i/2;for(int y=125;y>World.SEA_LEVEL;y--){BlockType ground=world.getBlock(px,y,pz);if(!ground.isSoil())continue;var above=world.getBlock(px,y+1,pz);if(above!=BlockType.AIR&&above!=BlockType.TALL_GRASS&&above!=BlockType.FLOWER_RED&&above!=BlockType.FLOWER_YELLOW)continue;if(world.getBlock(px,y+2,pz)!=BlockType.AIR)continue;var mob=new Mob(entry.type,px+.5f,y+1.001f,pz+.5f,new Random(random.nextLong()));mob.persistent=true;herd.add(mob);break;}}return herd;}
}
