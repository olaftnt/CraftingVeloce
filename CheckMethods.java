import java.lang.reflect.Method;
public class CheckMethods {
    public static void main(String[] args) throws Exception {
        Class<?> clazz = Class.forName("com.smashingmods.alchemistry.registry.RecipeRegistry");
        for (Method m : clazz.getMethods()) {
            System.out.println(m.getName());
        }
    }
}
