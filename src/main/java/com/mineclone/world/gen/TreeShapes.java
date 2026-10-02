package com.mineclone.world.gen;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import java.util.*;

/** Pure deterministic tree cells shared by V1 generation and scheduled sapling growth. */
public final class TreeShapes {
    public record Cell(int x,int y,int z,BlockType block){}
    private record Position(int x,int y,int z){}
    private static final class Cells {
        final LinkedHashMap<Position,Cell> cells=new LinkedHashMap<>();
        boolean inBounds(int x,int y,int z){return y>=0&&y<Chunk.SIZE_Y;}
        BlockType get(int x,int y,int z){Cell c=cells.get(new Position(x,y,z));return c==null?BlockType.AIR:c.block();}
        void set(int x,int y,int z,BlockType b){cells.put(new Position(x,y,z),new Cell(x,y,z,b));}
    }
    private TreeShapes(){}
    public static List<Cell> cells(int species,int x,int groundY,int z,long hash){
        Cells out=new Cells();
        switch(species){case 1->placeSpruce(out,x,groundY,z,hash);case 2->placeAcacia(out,x,groundY,z,hash);default->placeOak(out,x,groundY,z,hash);}
        return List.copyOf(out.cells.values());
    }
    private static void placeAcacia(Cells c, int x, int height, int z, long h) {
        int top = height + 5 + (int) ((h >>> 8) & 1);
        if (top + 2 >= Chunk.SIZE_Y) return;
        for (int y = height + 1; y <= top; y++) if (c.inBounds(x, y, z))
            c.set(x, y, z, BlockType.WOOD);
        leafDisc(c, x, top, z, 3, h);
        leafDisc(c, x, top + 1, z, 2, h >>> 4);
    }

    /**
     * Лиственное дерево: ствол и округлая крона в пять слоёв.
     *
     * Прежняя крона была ромбом в три слоя и не доставала даже до середины
     * ствола — дерево читалось палкой с комком на конце. Здесь крона шире
     * ствола втрое и опускается на три блока ниже вершины, так что ствол
     * виден ровно настолько, насколько он и должен быть виден у взрослого
     * дерева: нижней третью.
     *
     * Форма кроны — круг, а не ромб: у ромба углы срезаны по диагоналям, и
     * сверху он выдаёт сетку. Каждое дерево дополнительно обкусывается по
     * углам своим же хэшем, поэтому подряд стоящие деревья не выглядят
     * отштампованными.
     */
    private static void placeOak(Cells c, int x, int height, int z, long h) {
        // Ствол не ниже шести: крона начинается на три блока ниже вершины, а
        // листва в движке твёрдая. У пятиблочного ствола крона садилась на
        // высоту роста, и сквозь лес было не пройти.
        int th = 6 + (int) ((h >>> 7) & 0x3);            // 6..9
        int top = height + th;
        if (top + 2 >= Chunk.SIZE_Y)
            return;
        for (int i = 1; i <= th; i++)
            if (c.inBounds(x, height + i, z))
                c.set(x, height + i, z, BlockType.WOOD);

        // Радиусы снизу вверх: узкое основание, широкая середина, шапка.
        int[] radii = { 2, 3, 3, 2, 1 };
        for (int level = 0; level < radii.length; level++)
            leafDisc(c, x, top - 3 + level, z, radii[level], h + level * 31L);
    }

    /**
     * Хвойное: ярусы от самого низа ствола к вершине, через один поджатые.
     *
     * Ель — это юбка, а не шар: крона начинается у земли и сужается кверху.
     * Через ярус радиус уменьшается на единицу — отсюда ступенчатый силуэт,
     * по которому хвойное отличается от лиственного с любого расстояния.
     */
    private static void placeSpruce(Cells c, int x, int height, int z, long h) {
        int th = 7 + (int) ((h >>> 7) & 0x3);            // 7..10
        int top = height + th;
        if (top + 2 >= Chunk.SIZE_Y)
            return;
        for (int i = 1; i <= th; i++)
            if (c.inBounds(x, height + i, z))
                c.set(x, height + i, z, BlockType.WOOD);

        // Юбка начинается примерно с трети ствола: у самой земли она
        // превращала бы одно дерево в стену семь на семь — листва твёрдая.
        int bottom = height + 1 + th / 3;
        int span = Math.max(1, top - bottom);
        for (int y = bottom; y <= top; y++) {
            float t = (y - bottom) / (float) span;       // 0 внизу юбки, 1 у вершины
            int rad = Math.round(2.4f * (1f - t));
            if (((y - bottom) & 1) == 1)
                rad--;                                   // поджатый ярус
            if (rad < 0)
                continue;
            leafDisc(c, x, y, z, rad, h + y * 17L);
        }
        if (c.inBounds(x, top + 1, z) && c.get(x, top + 1, z) == BlockType.AIR)
            c.set(x, top + 1, z, BlockType.LEAVES);
    }

    /**
     * Один слой кроны: круг радиуса rad с обкусанными углами.
     *
     * Допуск {@code +0.2 * rad} расширяет круг ровно настолько, чтобы на
     * малых радиусах он не вырождался в крест. Углы отбрасываются по битам
     * хэша — это и даёт разнообразие, и не стоит ни одного вызова генератора
     * случайных чисел.
     */
    private static void leafDisc(Cells c, int x, int y, int z, int rad, long h) {
        if (rad < 0)
            return;
        float limit = rad * rad + rad * 0.2f;
        int bit = 0;
        for (int dx = -rad; dx <= rad; dx++)
            for (int dz = -rad; dz <= rad; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > limit)
                    continue;
                // Обкусываем только самый край и только у широких слоёв:
                // дырки в середине кроны видны насквозь.
                if (rad >= 2 && d2 >= (rad - 0.5f) * (rad - 0.5f)
                        && ((h >>> (bit++ & 31)) & 1L) == 0L)
                    continue;
                int lx = x + dx, lz = z + dz;
                if (c.inBounds(lx, y, lz) && c.get(lx, y, lz) == BlockType.AIR)
                    c.set(lx, y, lz, BlockType.LEAVES);
            }
    }

}
