package com.ab.abplugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import anywheresoftware.b4a.BA;
import anywheresoftware.b4a.BA.Author;
import anywheresoftware.b4a.BA.DesignerName;
import anywheresoftware.b4a.BA.Events;
import anywheresoftware.b4a.BA.ShortName;
import anywheresoftware.b4a.BA.Version;

@DesignerName("Build 20160528")                                    
@Version(1.00F)                                
@Author("Alain Bailleul")
@ShortName("ABPlugin") 
@Events(values={"PluginsChanged()"})
public class ABPlugin {	
	protected BA _ba;
	protected String _event;
	private String pluginsDir;
	private Map<String, ABPluginDefinition> plugins = new LinkedHashMap<String, ABPluginDefinition>(); 
	protected boolean mIsRunning=false;
	protected boolean mPluginIsRunning=false;
	protected boolean mIsLoading=false;
	protected ScheduledExecutorService service = null;
	protected Future<?> future = null;
	protected long CheckForNewIntervalMS=0;
	private static URLClassLoader parentClassLoader;
	private String AllowedKey="";
	
	public void Initialize(BA ba, String eventName, String pluginsDir, String allowedKey) {
		this._ba = ba;
		this._event = eventName.toLowerCase(BA.cul);
		this.pluginsDir = pluginsDir;
		this.AllowedKey=allowedKey;
		parentClassLoader = (java.net.URLClassLoader)Thread.currentThread().getContextClassLoader();
		File f = new File(pluginsDir);
		if (!f.exists()) {
			try {
				Files.createDirectories(Paths.get(pluginsDir));
			} catch (IOException e) {
				e.printStackTrace();
			}
		}				
	}	
	
	public anywheresoftware.b4a.objects.collections.List GetAvailablePlugins() {
		anywheresoftware.b4a.objects.collections.List ret = new anywheresoftware.b4a.objects.collections.List();
		ret.Initialize();
		for (Entry<String,ABPluginDefinition> entry : plugins.entrySet()) {
			ret.Add(entry.getValue().NiceName);
		}
		return ret;
	}
	
	Runnable runnable = new Runnable() {
	    public void run() {
	    	if (mIsRunning && !mPluginIsRunning) {
	    		mIsLoading=true;
	    		Map<String, Boolean> toRemove = new LinkedHashMap<String,Boolean>();
    			List<String> toAdd = new ArrayList<String>();
    			for (Entry<String,ABPluginDefinition> entry: plugins.entrySet()) {
    				toRemove.put(entry.getKey(), true);
    			}	
    			boolean NeedsReload=false;
    			File dh = new File(pluginsDir);
    			for (File f: dh.listFiles()) {
    				if (f.getName().endsWith(".jar")) {
    					String pluginName = f.getName().substring(0, f.getName().length()-4).toLowerCase();
    					toRemove.remove(pluginName);
    					if (!plugins.containsKey(pluginName)) {
    						toAdd.add(f.getAbsolutePath());    						
    					} else {
    						ABPluginDefinition def = plugins.get(pluginName);
    						if (f.lastModified()!=def.lastModified) {
    							toRemove.put(pluginName, true);    							 
    						}
    					}
    				}
    			}
    			if (toRemove.size()>0) {
    				toAdd = new ArrayList<String>();
    				for (Entry<String,ABPluginDefinition> entry: plugins.entrySet()) {
    					entry.getValue().objectClass = null;
    				}
    				plugins = new LinkedHashMap<String, ABPluginDefinition>();
    				for (File f: dh.listFiles()) {
        				if (f.getName().endsWith(".jar")) {
        					toAdd.add(f.getAbsolutePath());        					
        				}
        			}
    			}
    			boolean Added = false;
    			for (int i=0;i<toAdd.size();i++) {	    			
    				File f = new File(toAdd.get(i));
    				ABPluginDefinition def = new ABPluginDefinition();
    				def.lastModified = f.lastModified();
    				if (loadJarFile(pluginsDir, f, parentClassLoader, def)) {
    					if (RunInitialize(def)) {
    						def.NiceName = innerGetNiceName(def);
    						plugins.put(def.Name.toLowerCase(), def);
    						Added=true;    						
    					}
    				}
    			}    
    			
    			if (NeedsReload || Added) {    						
    				_ba.raiseEvent(this, _event + "_pluginschanged", new Object[] {});
    				
    			}
    			mIsLoading=false;
	    	}
	    }
	};
	
	private boolean RunInitialize(ABPluginDefinition def) {				
		java.lang.reflect.Method m;
		try {
			m = def.objectClass.getMethod("_initialize", new Class[]{anywheresoftware.b4a.BA.class});
			m.setAccessible(true);
			return (this.AllowedKey==(String) m.invoke(def.object, new Object[] {_ba}));			
		} catch (NoSuchMethodException e) {
			e.printStackTrace();
		} catch (SecurityException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
		} catch (InvocationTargetException e) {
			e.printStackTrace();
		}
		return false;
	}
	
	public Object RunPlugin(String pluginNiceName, String tag, anywheresoftware.b4a.objects.collections.Map params) {		
		mPluginIsRunning=true;
		ABPluginDefinition def=null;
		for (Entry<String,ABPluginDefinition> entry: plugins.entrySet()) {
			if (entry.getValue().NiceName.equalsIgnoreCase(pluginNiceName)) {
				def = entry.getValue();
				break;
			}
		}		
		if (def==null) {
			BA.Log("No plugin found with name: '" + pluginNiceName + "'");
			mPluginIsRunning=false;
			return null;
		}
		java.lang.reflect.Method m;
		try {
			m = GetMethod(def, "_run");
			if (m==null) {
				BA.Log("'Sub Run(Tag As String, Params As Map) As Object' not found!");
				mPluginIsRunning=false;
				return "";
			}
			m.setAccessible(true);
			Object ret = m.invoke(def.object, new Object[] {tag, params});
			mPluginIsRunning=false;
			return ret;		
		} catch (SecurityException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
		} catch (InvocationTargetException e) {
			e.printStackTrace();
		}	
		mPluginIsRunning=false;
		return null;
	}	
	
	protected String innerGetNiceName(ABPluginDefinition def) {		
		mPluginIsRunning=true;		
		java.lang.reflect.Method m;
		try {
			m = GetMethod(def, "_getnicename");
			if (m==null) {
				BA.Log("'Sub GetNiceName() As String' not found!");
				mPluginIsRunning=false;
				return "";
			}
			m.setAccessible(true);
			Object ret = m.invoke(def.object, new Object[] {});
			mPluginIsRunning=false;
			return (String)ret;		
		} catch (SecurityException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
		} catch (InvocationTargetException e) {
			e.printStackTrace();
		}	
		mPluginIsRunning=false;
		return "";
	}
	
	protected java.lang.reflect.Method GetMethod(ABPluginDefinition def, String methodName) {
		Class<?> clazz = def.objectClass;
		while (clazz != null) {
			java.lang.reflect.Method[] methods = clazz.getDeclaredMethods();
		    for (java.lang.reflect.Method method : methods) {		     
		        if (method.getName().equals(methodName)) {
		            return method;
		        }
		    }
		    clazz = clazz.getSuperclass();
		}
		return null;
	}
	
	public void Start(long checkForNewIntervalMS) {
		this.CheckForNewIntervalMS = checkForNewIntervalMS;
		service = Executors.newSingleThreadScheduledExecutor();
		future = service.scheduleAtFixedRate(runnable, 0, checkForNewIntervalMS, TimeUnit.MILLISECONDS);
		mIsRunning = true;
	}
	
	public void Stop() {		
		if (mIsRunning) {
			mIsRunning = false;
			future.cancel(true);
			service.shutdown();
		}	
	}
	
	public void Pauze() {
		if (mIsRunning) {
			mIsRunning = false;
			future.cancel(true);
		}		
	}
	
	public void Resume() {
		future = service.scheduleAtFixedRate(runnable, 0, CheckForNewIntervalMS, TimeUnit.MILLISECONDS);
	}	
	
	private boolean loadJarFile(String directoryName, File pluginFile, ClassLoader parentClassLoader, ABPluginDefinition def) {
        URL url = null;
        try {
            url = new URL("jar:file:" + directoryName + "/" + pluginFile.getName() + "!/");
        } catch (MalformedURLException e) {
            BA.Log("URL '" + url + "': " + e);
            return false;
        }
        URL[] urls = { url };
 
        JarFile jarFile = null;
        try {
            jarFile = new JarFile(pluginFile);
        } catch (IOException e) {
            BA.Log("JAR file '" + pluginFile.getName() + "': " + e);
            return false;
        }
        def.Name = pluginFile.getName().substring(0, pluginFile.getName().length()-4);
 
        // go through JAR file contents
        List<String> classes = new ArrayList<String>();
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = (JarEntry) entries.nextElement();
            if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                continue;
            }
            classes.add(entry.getName().replace(".class", "").replace('/', '.'));
        }
        
        def.objectClass=null;
        try (URLClassLoader classLoader = new URLClassLoader(urls, parentClassLoader)) {
        	for (int i=0;i<classes.size();i++) {            		
        		if (classes.get(i).toLowerCase().endsWith(def.Name.toLowerCase())) {
        			def.objectClass = classLoader.loadClass(classes.get(i));
        			def.object = def.objectClass.newInstance();
        		} else {
        			classLoader.loadClass(classes.get(i));
        		}
        	}
        } catch (ClassNotFoundException | IOException | InstantiationException | IllegalAccessException e) {
            BA.Log("" + e);
            try {
                jarFile.close();
            } catch (IOException eClose) {
            	BA.Log("Attempting to close JAR file: " + eClose);
            	return false;
            }
        }	     
                  
 
        try {
            jarFile.close();
        } catch (IOException e) {
        	BA.Log("Attempting to close JAR file: " + e);
        	return false;
        }
        return true;
	}

}
