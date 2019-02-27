package org.viritystila;


import java.nio.Buffer;
import java.nio.ByteBuffer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.BytePointer;
import org.opencv.core.Mat;
import org.bytedeco.javacpp.Loader;
import static org.bytedeco.javacpp.opencv_core.*;
public class opencvMatConvert 
{
    static { Loader.load(org.bytedeco.javacpp.opencv_core.class); }

    public static void main( String[] args )
    {
        System.out.println( "Hello World!" );
    }
    
    public static void printTest()
    {
        System.out.println( "Testing opencvMatConvert class \n" );
    }
    
    public java.nio.ByteBuffer convert(org.opencv.core.Mat mat) {
        if (mat == null) {
            return null;
        } else {
            ByteBuffer byteBuffer = new BytePointer() { { address = mat.dataAddr(); } }.capacity(mat.rows() * mat.step1() * mat.elemSize1()).asByteBuffer();
            return byteBuffer;
        }
    }
    
    public java.nio.ByteBuffer convertFromAddr(long dataAddr, int rows, long step1, long elemSize1) {
//         if (mat == null) {
//             return null;
//         } else {
            ByteBuffer byteBuffer = new BytePointer() { { address = dataAddr; } }.capacity(rows * step1 * elemSize1).asByteBuffer();
            return byteBuffer;
//         }
    }
}
