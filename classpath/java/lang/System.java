package java.lang;

public abstract class System {
  public static final Output out = new Output();
  public static final Output err = out;

  static {
    loadLibrary("natives");
  }

  public static native void arraycopy(Object src, int srcOffset, Object dst,
                                      int dstOffset, int length);

  public static native String getProperty(String name);

  public static void loadLibrary(String name) {
    Runtime.getRuntime().loadLibrary(name);
  }

  public static void gc() {
    Runtime.getRuntime().gc();
  }

  public static void exit(int code) {
    Runtime.getRuntime().exit(code);
  }

  public static class Output {
    public synchronized native void print(String s);

    public synchronized void println(String s) {
      print(s);
      print(getProperty("line.separator"));
    }
  }
}
