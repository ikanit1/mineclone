import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.imageio.ImageIO;

/** Imports the reviewed 4x4 food/material sheet as hard-alpha native inventory sprites. */
public final class ImportFarmItemTextures {
    public static void main(String[] args)throws Exception{
        Path source=Path.of("assets/textures/source/farm-items-2026-10-02.png");
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        if(!Files.readString(source.resolveSibling("farm-items-2026-10-02.json")).contains(digest))throw new IllegalStateException("source digest mismatch");
        BufferedImage sheet=ImageIO.read(source.toFile());
        String[] names={"wheat","wheat_seeds","bread","carrot","potato","baked_potato","apple","golden_apple","rotten_flesh","bone","gunpowder","string","slimeball","leather","feather","egg"};
        Path output=Path.of(args.length==0?"assets/textures/blocks":args[0]);Files.createDirectories(output);
        for(int cell=0;cell<16;cell++){
            BufferedImage tile=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
            for(int y=1;y<31;y++)for(int x=1;x<31;x++){
                int px=(int)((cell%4+(x+.5)/32)*sheet.getWidth()/4);
                int py=(int)((cell/4+(y+.5)/32)*sheet.getHeight()/4);
                int pixel=sheet.getRGB(px,py);tile.setRGB(x,y,pixel>>>24>=240?pixel|0xff000000:0);
            }
            ImageIO.write(tile,"png",output.resolve(names[cell]+".png").toFile());
        }
    }
}
