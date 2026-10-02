import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.imageio.ImageIO;

/** Deterministic nearest-neighbor import of reviewed armor panels to 64x32 sheets. */
public final class ImportArmorSheet {
    public static void main(String[] args)throws Exception{
        Path source=Path.of("assets/textures/source/armor-models-2026-10-02.png");
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        if(!Files.readString(source.resolveSibling("armor-models-2026-10-02.json")).contains(digest))throw new IllegalStateException("source digest mismatch");
        BufferedImage sheet=ImageIO.read(source.toFile());String[] materials={"leather","copper","iron","diamond"};
        Path out=Path.of(args.length==0?"assets/mobs/armor":args[0]);Files.createDirectories(out);
        for(int row=0;row<4;row++){
            var image=new BufferedImage(64,32,BufferedImage.TYPE_INT_ARGB);
            for(int tile=0;tile<8;tile++)for(int y=0;y<16;y++)for(int x=0;x<16;x++){
                boolean alpha=tile==0||tile==5||tile==6;
                double u=alpha?(x+.5)/16:.3+(x+.5)/16*.4;
                double v=alpha?(y+.5)/16:.25+(y+.5)/16*.4;
                int px=(int)((tile+u)*sheet.getWidth()/8),py=(int)((row+v)*sheet.getHeight()/4);
                int c=sheet.getRGB(px,py);image.setRGB(tile%4*16+x,tile/4*16+y,c>>>24>=240?c|0xff000000:0);
            }
            ImageIO.write(image,"png",out.resolve(materials[row]+".png").toFile());
        }
    }
}
