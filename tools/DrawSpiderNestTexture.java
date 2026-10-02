import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Offline original pixel artwork for LW-04; the game loads the resulting PNG only. */
public final class DrawSpiderNestTexture {
    public static void main(String[] args) throws Exception {
        Path target=Path.of("assets/textures/blocks/spider_nest.png");
        BufferedImage tile=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<32;y++)for(int x=0;x<32;x++){
            int fleck=Math.floorMod(x*31+y*17+x*y*3,13)-6;
            int edge=Math.min(Math.min(x,31-x),Math.min(y,31-y))<2?-5:0;
            tile.setRGB(x,y,new Color(53+fleck+edge,43+fleck+edge,32+fleck+edge).getRGB());
        }
        Graphics2D g=tile.createGraphics();
        // Irregular interlocked, decayed twigs around a hollow, never a plank grid.
        line(g,0x786047,0,6,4,3,12,4,21,1,29,3,31,8);
        line(g,0x29231d,0,8,5,5,12,6,22,3,28,5,31,10);
        line(g,0x85664b,1,27,5,22,3,15,6,9,4,4);
        line(g,0x30271d,3,29,7,22,5,15,8,10,6,5);
        line(g,0x73583f,6,31,12,27,20,29,27,25,31,26);
        line(g,0x34291e,5,29,12,25,20,27,27,23,31,24);
        line(g,0x796046,31,5,27,12,28,18,24,25,27,31);
        line(g,0x35281e,29,4,25,12,26,18,22,25,25,31);
        g.setColor(new Color(0x28251f));
        g.fillOval(7,7,19,21);
        // Loose silk anchored to the rim, with deliberate gaps and asymmetric strands.
        line(g,0x8e9586,0,0,7,9,11,12,18,15,29,27,31,31);
        line(g,0x858d80,31,0,24,7,20,11,14,18,8,24,0,31);
        line(g,0x747e72,0,17,6,15,12,14,19,17,26,20,31,18);
        line(g,0x99a08d,10,0,12,7,13,13,17,21,21,31);
        line(g,0x8b9381,4,8,12,6,21,7,27,12);
        line(g,0xa4aa96,5,21,7,26,13,28,20,27,25,22);
        line(g,0x626e62,5,12,7,10,11,9,21,10,25,14,25,20);
        // Five silk egg sacs, warm chalk highlights and cool shaded undersides.
        egg(g,12,16); egg(g,18,12); egg(g,21,20); egg(g,15,24); egg(g,16,18);
        line(g,0xced0b8,7,19,11,20,16,20,21,16,24,16);
        line(g,0xa5af9c,8,13,11,11,16,10,23,12);
        g.setColor(new Color(0xd5d7bf));
        g.fillRect(3,4,1,1);g.fillRect(28,8,1,1);g.fillRect(26,26,1,1);g.fillRect(7,27,1,1);
        g.dispose();
        Files.createDirectories(target.getParent());
        ImageIO.write(tile,"PNG",target.toFile());
        System.out.println("SPIDER_NEST_TEXTURE 32x32 original hard-pixel RGBA: "+target);
    }
    private static void egg(Graphics2D g,int x,int y){
        g.setColor(new Color(0x131c19));g.fillRect(x-3,y-3,7,8);
        g.setColor(new Color(0x737e70));g.fillRect(x-2,y-3,5,7);g.fillRect(x-3,y-1,7,3);
        g.setColor(new Color(0xb8bea5));g.fillRect(x-2,y-3,4,5);g.fillRect(x-3,y-1,6,2);
        g.setColor(new Color(0xe2dec2));g.fillRect(x-1,y-3,2,1);g.fillRect(x-2,y-2,3,2);
        g.setColor(new Color(0x929d8a));g.fillRect(x-1,y+2,3,1);
    }
    private static void line(Graphics2D g,int rgb,int... points){
        g.setColor(new Color(rgb));
        for(int i=2;i<points.length;i+=2)g.drawLine(points[i-2],points[i-1],points[i],points[i+1]);
    }
}
