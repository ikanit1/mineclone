import com.mineclone.render.TextureAtlas;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.Base64;

/** Exports the actual runtime PNG registry, never the legacy procedural approximations. */
public final class ExportTextureCatalog {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length == 0 ? "docs/textures.html" : args[0]);
        StringBuilder html = new StringBuilder("""
            <!doctype html><html lang="ru"><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Mineclone — каталог текстур</title>
            <style>body{background:#17212b;color:#edf1f4;font:16px system-ui;margin:32px}
            #tiles{display:grid;grid-template-columns:repeat(auto-fill,minmax(165px,1fr));gap:12px}
            figure{margin:0;padding:12px;background:#263441;border-radius:8px}
            img{width:128px;height:128px;image-rendering:pixelated;background:repeating-conic-gradient(#344553 0% 25%,#283641 0% 50%) 0/16px 16px}
            figcaption{margin-top:8px;font:12px monospace;overflow-wrap:anywhere}input{padding:8px;margin:0 0 20px}</style>
            <h1>Текстуры Mineclone</h1><p>Настоящие PNG и индексы игрового атласа, 32×32.</p>
            <input id="query" aria-label="Поиск текстуры" placeholder="Название или индекс"><div id="tiles">
            """);
        for (int i = 0; i < TextureAtlas.TILE_NAMES.length; i++) {
            String name = TextureAtlas.TILE_NAMES[i];
            Path source = Path.of(TextureAtlas.BLOCKS_DIR, name + ".png");
            if (ImageIO.read(source.toFile()) == null) throw new IllegalArgumentException("unreadable sprite: " + source);
            html.append("<figure data-name=\"").append(i).append(' ').append(name).append("\"><img alt=\"")
                .append(name).append("\" src=\"data:image/png;base64,")
                .append(Base64.getEncoder().encodeToString(Files.readAllBytes(source)))
                .append("\"><figcaption>").append(i).append(" · ").append(name).append("</figcaption></figure>\n");
        }
        html.append("""
            </div><script>document.querySelector('#query').addEventListener('input',e=>{
            const q=e.target.value.toLowerCase();document.querySelectorAll('figure').forEach(f=>f.hidden=!f.dataset.name.includes(q));
            });</script></html>
            """);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, html);
        System.out.println("Exported " + TextureAtlas.TILE_NAMES.length + " actual tiles to " + out);
    }
}
