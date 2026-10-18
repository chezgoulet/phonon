package android.util
object Log {
  @JvmStatic fun e(t:String,m:String):Int{System.err.println("E $m");return 0}
  @JvmStatic fun w(t:String,m:String):Int{System.err.println("W $m");return 0}
  @JvmStatic fun i(t:String,m:String):Int{System.err.println("I $m");return 0}
}
