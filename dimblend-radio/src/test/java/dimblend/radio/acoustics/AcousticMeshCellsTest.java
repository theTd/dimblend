package dimblend.radio.acoustics;

import java.util.Random;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AcousticMeshCellsTest {
    @Test void mergedSurfaceAreaMatchesExposedVoxelsForAllAxesAndMaterials() {
        int[] size={7,5,9};
        byte[] cells=new byte[size[0]*size[1]*size[2]];
        Random random=new Random(718);
        for(int i=0;i<cells.length;i++)cells[i]=random.nextBoolean()?(byte)(1+random.nextInt(5)):0;
        var mesh=new AcousticMesh(Vec3.ZERO);
        mesh.appendCells(cells,new int[]{-12,63,17},size,null);
        var data=mesh.data();
        double area=0;
        for(int i=0;i<data.triangles().length;i+=3){
            Vec3 a=vertex(data,i),b=vertex(data,i+1),c=vertex(data,i+2);
            area+=b.subtract(a).cross(c.subtract(a)).length()/2;
            assertTrue(data.materials()[i/3]>=0&&data.materials()[i/3]<5);
        }
        int faces=0;
        int[][] directions={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for(int z=0;z<size[2];z++)for(int y=0;y<size[1];y++)for(int x=0;x<size[0];x++){
            if(cells[(z*size[1]+y)*size[0]+x]==0)continue;
            for(int[] d:directions){
                int nx=x+d[0],ny=y+d[1],nz=z+d[2];
                if(nx<0||ny<0||nz<0||nx>=size[0]||ny>=size[1]||nz>=size[2]
                        ||cells[(nz*size[1]+ny)*size[0]+nx]==0)faces++;
            }
        }
        assertEquals(faces,area,1e-6);
    }

    @Test void largeUniformVolumeMergesToSixFaces() {
        int[] size={64,48,80};
        byte[] cells=new byte[size[0]*size[1]*size[2]];
        java.util.Arrays.fill(cells,(byte)5);
        var mesh=new AcousticMesh(Vec3.ZERO);
        long start=System.nanoTime();
        mesh.appendCells(cells,new int[]{0,0,0},size,null);
        System.out.printf("[mesh] 245760-cell greedy surface extraction %.3f ms%n",(System.nanoTime()-start)/1e6);
        assertEquals(12,mesh.data().triangles().length/3);
    }

    private static Vec3 vertex(AcousticMesh.Data data,int index){
        int offset=data.triangles()[index]*3;
        return new Vec3(data.vertices()[offset],data.vertices()[offset+1],data.vertices()[offset+2]);
    }
}
