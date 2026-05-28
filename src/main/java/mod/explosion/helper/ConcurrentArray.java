package mod.explosion.helper;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public class ConcurrentArray<T> {

	private static final VarHandle HANDLE = MethodHandles.arrayElementVarHandle(Object[].class);
	private static final Object SETTER = new Object();
	
	private final Object[] array;
	
	public ConcurrentArray(int size) {
		array = new Object[size];
	}
	
	public int size() {
		return array.length;
	}
	
	@SuppressWarnings("unchecked")
	public T get(int index) {
		Object value = HANDLE.getVolatile(array, index);
		
		while (value == SETTER) {
			Thread.onSpinWait();
			value = HANDLE.getVolatile(array, index);
		}
		
		return (T)value;
	}
	
	public boolean enterWrite(int index) {
		return HANDLE.compareAndSet(array, index, null, SETTER);
	}
	
	public T set(int index, T value) {
		HANDLE.setVolatile(array, index, value);
		return value;
	}
	
	public void clear(int index) {
		HANDLE.setVolatile(array, index, null);
	}
}
