package com.dianping.pigeon.console.servlet.json;

import com.dianping.pigeon.config.ConfigManager;
import com.dianping.pigeon.config.ConfigManagerLoader;
import com.dianping.pigeon.console.domain.MavenCoordinate;
import com.dianping.pigeon.console.domain.ServicePath;
import com.dianping.pigeon.console.domain.ServicePaths;
import com.dianping.pigeon.console.servlet.ServiceServlet;
import com.dianping.pigeon.remoting.provider.config.ProviderConfig;
import com.dianping.pigeon.remoting.provider.config.ServerConfig;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Created by shihuashen on 16/10/24.
 */
public class JarJsonServlet extends ServiceServlet {
    private static ConcurrentMap<String, ServicePath> pathMap = new ConcurrentHashMap<String, ServicePath>();
    private static ConcurrentMap<String, List<MavenCoordinate>> jarMap = new ConcurrentHashMap<String, List<MavenCoordinate>>();
    private static ConfigManager configManager = ConfigManagerLoader.getConfigManager();

    public JarJsonServlet(ServerConfig serverConfig, int port) {
        super(serverConfig, port);
    }

    private ServicePaths getServicePaths() {
        List<ServicePath> paths = new LinkedList<ServicePath>();
        Map<String, ProviderConfig<?>> serviceProviders = getServiceProviders();
        for (Map.Entry<String, ProviderConfig<?>> entry : serviceProviders.entrySet()) {
            String serviceName = entry.getKey();
            ServicePath servicePath = pathMap.get(serviceName);
            if (servicePath != null) {
                paths.add(servicePath);
            } else {
                servicePath = new ServicePath();
                servicePath.setService(serviceName);
                String group = configManager.getGroup();
                servicePath.setGroup(group);
                ProviderConfig<?> providerConfig = entry.getValue();
                Class<?> serviceInterface = providerConfig.getServiceInterface();
                URL url = serviceInterface.getResource(serviceInterface.getSimpleName() + ".class");
                if (url != null) {
                    String path = url.getFile();
                    servicePath.setPath(trimPath(path));
                }
                MavenCoordinate coordinate = getCoordinate(serviceInterface);
                if (coordinate != null) {
                    servicePath.setGroupId(coordinate.getGroupId());
                    servicePath.setArtifactId(coordinate.getArtifactId());
                    servicePath.setVersion(coordinate.getVersion());
                    servicePath.setTime(coordinate.getTime());
                }
                pathMap.put(serviceName, servicePath);
                paths.add(servicePath);
            }
        }
        ServicePaths servicePaths = new ServicePaths();
        servicePaths.setPaths(paths);
        return servicePaths;
    }

    @Override
    protected boolean initServicePage(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ServicePaths paths = null;
        paths = getServicePaths();
        this.model = paths;
        return true;
    }

    @Override
    public String getView() {
        return "Jars.ftl";
    }

    @Override
    public String getContentType() {
        return "application/json; charset=UTF-8";
    }


    private MavenCoordinate getCoordinate(Class<?> serviceInterface) {
//        MavenCoordinate coordinate = null;
//        coordinate = getFromManifest(serviceInterface);
//        if(coordinate!=null)
//            return coordinate;
        return detect(serviceInterface);

    }


    private MavenCoordinate getFromManifest(Class<?> serviceInterface) {
        MavenCoordinate mavenCoordinate = new MavenCoordinate();
        Package aPackage = serviceInterface.getPackage();
        String artifactId = aPackage.getImplementationTitle();
        String groupId = aPackage.getImplementationVendor();
        String version = aPackage.getImplementationVersion();
        mavenCoordinate.setArtifactId(artifactId);
        mavenCoordinate.setGroupId(groupId);
        mavenCoordinate.setVersion(version);
        boolean validated = false;
        try {
            validated = validate(mavenCoordinate, serviceInterface);
        } catch (IOException e) {
            logger.info(e);
        }
        if (validated)
            return mavenCoordinate;
        else
            return null;
    }

    private MavenCoordinate detect(Class<?> serviceInterface) {
        String jarFilePath = null;
        try {
            jarFilePath = serviceInterface.getProtectionDomain().getCodeSource().getLocation().getFile();
        } catch (SecurityException e) {
            logger.info("Permission deny " + e);
        } catch (NullPointerException e) {
            logger.info("Null pointer in get jar path");
        } catch (Throwable t) {
            logger.info(t);
        }
        if (jarFilePath == null)
            return null;
        List<MavenCoordinate> coordinates = jarMap.get(jarFilePath);
        if (coordinates == null) {
            coordinates = new LinkedList<MavenCoordinate>();
            JarFile jarFile = null;
            try {
                jarFile = new JarFile(jarFilePath);
                Enumeration<JarEntry> files = jarFile.entries();
                while (files.hasMoreElements()) {
                    JarEntry entry = files.nextElement();
                    if (entry.getName().endsWith("pom.properties")) {
                        MavenCoordinate coordinate = new MavenCoordinate();
                        InputStream in = null;
                        try {
                            in = serviceInterface.getClassLoader().getResourceAsStream(entry.getName());
                            Properties p = new Properties();
                            p.load(in);
                            coordinate.setVersion(p.getProperty("version"));
                            coordinate.setArtifactId(p.getProperty("artifactId"));
                            coordinate.setGroupId(p.getProperty("groupId"));
                        } catch (IOException e) {
                            logger.info(e);
                            return null;
                        } finally {
                            if (in != null) {
                                in.close();
                            }
                        }
                        try {
                            in = serviceInterface.getClassLoader().getResourceAsStream(entry.getName());
                            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
                            reader.readLine();
                            coordinate.setTime(reader.readLine());
                            in.close();
                        } catch (IOException e) {
                            logger.info(e);
                            return null;
                        } finally {
                            if (in != null)
                                in.close();
                        }
                        coordinates.add(coordinate);
                    }
                }
                jarMap.put(jarFilePath, coordinates);
            } catch (IOException e) {
                logger.info(e);
                return null;
            }
        }
        for (MavenCoordinate coordinate : coordinates) {
            String jarName = coordinate.getArtifactId() + "-" + coordinate.getVersion() + ".jar";
            String actualJarName = getJarName(jarFilePath);
            if (jarName.equals(actualJarName)) {
                return coordinate;
            }
        }
        return null;
    }

    private boolean validate(MavenCoordinate mavenCoordinate, Class<?> serviceInterface) throws IOException {
        if (mavenCoordinate.getArtifactId() != null && mavenCoordinate.getGroupId() != null) {
            InputStream in = serviceInterface.getClassLoader().getResourceAsStream("META-INF/maven." + mavenCoordinate.getGroupId() + "." + mavenCoordinate.getArtifactId() + "/pom.properties");
            Properties p = new Properties();
            p.load(in);
            String version = p.getProperty("version");
            String groupId = p.getProperty("groupId");
            String artifactId = p.getProperty("artifactId");
            in.close();
            if (groupId.equals(mavenCoordinate.getGroupId())
                    && artifactId.equals(mavenCoordinate.getArtifactId())) {
                in = serviceInterface.getClassLoader().getResourceAsStream("META-INF/maven." + mavenCoordinate.getGroupId() + "." + mavenCoordinate.getArtifactId() + "/pom.properties");
                BufferedReader reader = new BufferedReader(new InputStreamReader(in));
                reader.readLine();
                String time = reader.readLine();
                reader.close();
                in.close();
                mavenCoordinate.setTime(time);
                mavenCoordinate.setVersion(version);
                return true;
            }
            return false;
        }
        return false;
    }

    private String getJarName(String jarFilePath) {
        String[] strs = jarFilePath.split("/");
        if (strs.length != 0) {
            return strs[strs.length - 1];
        }
        return null;
    }

    private String trimPath(String path) {
        int index = path.indexOf("!");
        String jarPath = path.substring(0, index);
        String[] strs = jarPath.split("/");
        if (strs.length > 0) {
            String jarName = strs[strs.length - 1];
            return jarName + path.substring(index);
        }
        return null;
    }
}