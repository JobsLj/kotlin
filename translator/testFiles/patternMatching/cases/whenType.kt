namespace foo

class A() {

}

fun box() : Boolean {
  when(A()) {
    is A => return true; 
    else => return false;
  }
}