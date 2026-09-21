import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Hand-drawn 8x8 masks enlarged without antialiasing, with binary alpha. */
public class DrawPixelParticles {
    static void draw(String name, String[] rows, int[] colors) throws Exception {
        BufferedImage img=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<8;y++) for(int x=0;x<8;x++) {
            char p=rows[y].charAt(x); if(p=='.') continue;
            for(int dy=0;dy<4;dy++) for(int dx=0;dx<4;dx++)
                img.setRGB(x*4+dx,y*4+dy,0xff000000|colors[p-'0']);
        }
        ImageIO.write(img,"png",new File("assets/textures/blocks/pixel_"+name+".png"));
    }
    public static void main(String[] args) throws Exception {
        draw("spark",new String[]{"........","........","..1111..","..1221..","..1221..","..1111..","........","........"},new int[]{0x909090,0xdddddd,0xffffff});
        draw("water",new String[]{"........","...11...","..1221..","..1231..","..1221..","...11...","........","........"},new int[]{0x1753aa,0x3975cc,0x73b6ef,0xd9f5ff});
        draw("smoke",new String[]{"........","..11....",".12211..",".122221.","..22221.",".122211.","..1111..","........"},new int[]{0x777777,0xaaaaaa,0xffffff});
        draw("flame",new String[]{"...1....","...11...","..122...","..1231..",".123321.",".123321.","..1221..","...11..."},new int[]{0x7c1700,0xec490b,0xffae22,0xffef99});
        draw("lava",new String[]{"........","..111...",".12221..",".12321..",".12221..","..121...","...1....","........"},new int[]{0x751600,0xb93708,0xff8618,0xffe579});
        draw("snow",new String[]{"........","...11...","...21...",".122221.",".112211.","...21...","...11...","........"},new int[]{0xaaccee,0xd4e5f8,0xffffff});
    }
}
