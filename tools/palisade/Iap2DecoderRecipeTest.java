package hmgapi.engine;
import java.nio.file.*;
import java.util.*;

/** Apply the production MemRecipe to actual ELF mappings, retaining the SDK body. */
public final class Iap2DecoderRecipeTest {
    static int u16(byte[]b,int p){return(b[p]&255)|((b[p+1]&255)<<8);}
    static int u32(byte[]b,int p){return u16(b,p)|(u16(b,p+2)<<16);}
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    public static void main(String[]a)throws Exception{
        byte[]stock=Files.readAllBytes(Paths.get(a[0]));
        check(MemRecipe.md5(stock).equals("fc7cb675b46a44bd893a1edc054c5e71"),"stock MD5");
        MemRecipe r=MemRecipe.parse(new String(Files.readAllBytes(Paths.get(a[1])),"UTF-8"));
        int ph=u32(stock,28),n=u16(stock,44),stride=u16(stock,42),end=0,text=0;
        for(int k=0;k<n;k++){int p=ph+k*stride;if(u32(stock,p)!=1)continue;end=Math.max(end,u32(stock,p+8)+u32(stock,p+20));if(u32(stock,p+4)==0&&(u32(stock,p+24)&1)!=0)text=(u32(stock,p+20)+4095)&~4095;}
        byte[]image=new byte[end];for(int k=0;k<n;k++){int p=ph+k*stride;if(u32(stock,p)==1)System.arraycopy(stock,u32(stock,p+4),image,u32(stock,p+8),u32(stock,p+16));}
        int[]shifts={0,0x300000,-0x300000,0xf00000};
        int rejected=0;
        for(MemRecipe.Verify v:r.verifies){
            final byte[] bad=image.clone();bad[(int)v.off]^=1;final int[] writes={0};
            MemRecipe.Memory m=new MemRecipe.Memory(){
                public byte[]peek(long at,int len){return Arrays.copyOfRange(bad,(int)(at-0x40000000L),(int)(at-0x40000000L)+len);}
                public boolean poke(long at,byte[]b){writes[0]++;return true;}
                public long mapCode(long hint,int len){writes[0]++;return 0x40040000L;}
            };
            check(!r.apply(m,0x40000000L,text,MemRecipe.md5(stock)).ok(),"refuse changed fingerprint "+v.off);
            check(writes[0]==0,"fingerprint mismatch must precede every write");rejected++;
        }
        for(int k=0;k<shifts.length;k++){
            final long code=0x40040000L+shifts[k];final byte[]seg=new byte[4096],lib=image.clone();
            MemRecipe.Memory m=new MemRecipe.Memory(){
                public byte[]peek(long at,int len){return Arrays.copyOfRange(lib,(int)(at-0x40000000L),(int)(at-0x40000000L)+len);}
                public boolean poke(long at,byte[]b){byte[]target=at==code?seg:lib;int off=at==code?0:(int)(at-0x40000000L);System.arraycopy(b,0,target,off,b.length);return true;}
                public long mapCode(long hint,int len){return code;}
            };
            check(r.apply(m,0x40000000L,text,MemRecipe.md5(stock)).ok(),"apply "+k);
            for(long[]p:r.rel32)check((int)code+(int)p[1]+u32(seg,(int)p[0])==0x40000000+(int)p[2],"relative target "+k);
            // Outside entry branches, the original firmware mapping stays identical.
            byte[] restored=lib.clone();for(long[]b:r.branches)System.arraycopy(image,(int)b[0],restored,(int)b[0],4);
            check(Arrays.equals(restored,image),"unexpected firmware modification");
            Path out=Paths.get(a[2],"placement-"+k);Files.createDirectories(out);
            Files.write(out.resolve("session.bin"),lib);Files.write(out.resolve("segment.bin"),seg);
            Files.write(out.resolve("segment-address.txt"),Long.toString(code).getBytes("UTF-8"));
        }
        System.out.println("Production MemRecipe applied at four placements; "+rejected+" fingerprint changes refused before writes.");
    }
}
