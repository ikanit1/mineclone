import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.imageio.ImageIO;

/** Imports the reviewed three-cell vegetation sheet as hard-alpha native inventory sprites. */
public final class ImportVegetationTextures {
    public static void main(String[] args)throws Exception{
        Path source=Path.of("assets/textures/source/vegetation-2026-10-02.png");
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        if(!Files.readString(source.resolveSibling("vegetation-2026-10-02.json")).contains(digest))throw new IllegalStateException("source digest mismatch");
        BufferedImage sheet=ImageIO.read(source.toFile());
        String[] names={"tall_grass","flower_yellow","flower_red"};
        Path output=Path.of(args.length==0?"assets/textures/blocks":args[0]);Files.createDirectories(output);
        for(int cell=0;cell<3;cell++){
            BufferedImage tile=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
            for(int y=1;y<31;y++)for(int x=1;x<31;x++){
                int px=(int)((cell+(x+.5)/32)*sheet.getWidth()/3);
                int py=(int)(((y+.5)/32)*sheet.getHeight());
                int pixel=sheet.getRGB(px,py);tile.setRGB(x,y,pixel>>>24>=240?pixel|0xff000000:0);
            }
            ImageIO.write(tile,"png",output.resolve(names[cell]+".png").toFile());
        }
    }
}
