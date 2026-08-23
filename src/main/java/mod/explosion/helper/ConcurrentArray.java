package mod.explosion.helper;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class ConcurrentArray<T> {

	private static final VarHandle HANDLE = MethodHandles.arrayElementVarHandle(CompletableFuture[].class);
	
	private final CompletableFuture<?>[] array;
	
	public ConcurrentArray(int size) {
		array = new CompletableFuture[size];
	}
	
	public int size() {
		return array.length;
	}
	
	@SuppressWarnings("unchecked")
	public T get(int index) {
		CompletableFuture<?> value = (CompletableFuture<?>)HANDLE.getVolatile(array, index);
		if (value == null) {
			return null;
		}
		return (T)value.join();
	}
	
	@SuppressWarnings("unchecked")
	public T trySet(int index, Supplier<T> valueSupplier) {
		CompletableFuture<T> value = new CompletableFuture<T>();
		if (HANDLE.compareAndSet(array, index, null, value)) {
			value.complete(valueSupplier.get());
			return value.join();
		}
		CompletableFuture<?> current = (CompletableFuture<?>)HANDLE.getVolatile(array, index);
		return current == null ? null : (T)current.join();
	}
	
	public void clear(int index) {
		HANDLE.setVolatile(array, index, null);
	}
}
