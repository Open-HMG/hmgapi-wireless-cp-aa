package hmgapi.hook;
import java.util.ArrayList;
import java.util.List;
public class Cluster {
    public static final Cluster instance=new Cluster();
    public final List<Object[]> calls=new ArrayList<Object[]>();
    public static Cluster of(android.content.Context c) { return instance; }
    public void tbtInfo(int a,int b,float c,float d,int e,int f,int g,int h,int i) {
        calls.add(new Object[]{a,b,c,d,e,f,g,h,i});
    }
}
