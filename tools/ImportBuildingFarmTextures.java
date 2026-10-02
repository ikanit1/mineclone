import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.imageio.ImageIO;

/** Reproducible nearest-centre importer for the inspected building/farm source sheet. */
public final class ImportBuildingFarmTextures {
    public static void main(String[] args) throws Exception {
        Path source=Path.of("assets/textures/source/building-farm-2026-10-02.png");
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        String receipt=Files.readString(source.resolveSibling("building-farm-2026-10-02.json"));
        if(!receipt.contains(digest))throw new IllegalStateException("source digest does not match receipt");
        BufferedImage sheet=ImageIO.read(source.toFile());
        Path output=Path.of(args.length==0?"assets/textures/blocks":args[0]);Files.createDirectories(output);
        String[] names={"ladder","trapdoor","fence_gate","farmland_dry","farmland_wet","oak_sapling","spruce_sapling","acacia_sapling"};
        for(int cell=0;cell<names.length;cell++){
            int cx=cell%4,cy=cell/4;
            int x0=Math.round(cx*sheet.getWidth()/4f),x1=Math.round((cx+1)*sheet.getWidth()/4f);
            int y0=Math.round(cy*sheet.getHeight()/2f),y1=Math.round((cy+1)*sheet.getHeight()/2f);
            boolean soil=cell==3||cell==4;
            // Soil is an opaque repeating surface: use the authored interior, excluding source-sheet margins.
            if(soil){int mx=Math.round((x1-x0)*.065f),my=Math.round((y1-y0)*.065f);x0+=mx;x1-=mx;y0+=my;y1-=my;}
            BufferedImage tile=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<32;y++)for(int x=0;x<32;x++){
                int px=Math.min(x1-1,x0+(int)((x+.5f)*(x1-x0)/32));
                int py=Math.min(y1-1,y0+(int)((y+.5f)*(y1-y0)/32));
                int pixel=sheet.getRGB(px,py),alpha=pixel>>>24;
                pixel=alpha>=240?pixel|0xff000000:0;
                if(!soil&&(x==0||x==31||y==0||y==31))pixel=0;
                if(soil&&pixel>>>24!=255)throw new IllegalStateException("soil source is not opaque at "+names[cell]+":"+x+","+y);
                tile.setRGB(x,y,pixel);
            }
            ImageIO.write(tile,"png",output.resolve(names[cell]+".png").toFile());
        }
    }
}
