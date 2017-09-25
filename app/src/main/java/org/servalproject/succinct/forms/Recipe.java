package org.servalproject.succinct.forms;

import android.util.Log;

public class Recipe {
	private long ptr;
	private String recipe;
	byte[] hash;
	String formName;

	private native void buildRecipe(String content);
	private static native void closeRecipe(long ptr);
	private static native String stripForm(long recipe, String instance);
	private static native byte[] compressForm(long stats, long recipe, String strippedForm);

	private static String TAG = "Recipe";

	Recipe(String content){
		Log.v(TAG, "buildRecipe");
		buildRecipe(content);
	}

	private void callback(String formName, byte[] hash, String recipe, long ptr){
		Log.v(TAG, "Callback "+formName+", "+recipe);
		this.formName = formName;
		this.hash = hash;
		this.recipe = recipe;
		this.ptr = ptr;
	}

	public byte[] compress(Stats stats, String formInstance){
		String stripped = stripForm(this.ptr, formInstance);
		return compressForm(stats.ptr, this.ptr, stripped);
	}

	void close(){
		Log.v(TAG, "closeRecipe");
		closeRecipe(ptr);
		ptr = 0;
	}
}
