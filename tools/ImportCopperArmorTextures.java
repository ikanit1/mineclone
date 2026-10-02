import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.imageio.ImageIO;

/** Imports the reviewed four-cell copper armor sheet as hard-alpha native inventory sprites. */
public final class ImportCopperArmorTextures {
    public static void main(String[] args)throws Exception{
        Path source=Path.of("assets/textures/source/copper-armor-2026-10-02.png");
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        if(!Files.readString(source.resolveSibling("copper-armor-2026-10-02.json")).contains(digest))throw new IllegalStateException("source digest mismatch");
        BufferedImage sheet=ImageIO.read(source.toFile());
        String[] names={"copper_helmet","copper_chestplate","copper_leggings","copper_boots"};
        Path output=Path.of(args.length==0?"assets/textures/blocks":args[0]);Files.createDirectories(output);
        for(int cell=0;cell<4;cell++){
            BufferedImage tile=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
            for(int y=1;y<31;y++)for(int x=1;x<31;x++){
                int px=(int)((cell+(x+.5)/32)*sheet.getWidth()/4);
                int py=(int)(((y+.5)/32)*sheet.getHeight());
                int pixel=sheet.getRGB(px,py);tile.setRGB(x,y,pixel>>>24>=240?pixel|0xff000000:0);
            }
            ImageIO.write(tile,"png",output.resolve(names[cell]+".png").toFile());
        }
    }
}
