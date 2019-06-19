package ru.fix.dynamic.property.zk;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.TreeCache;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.fix.dynamic.property.api.DynamicPropertyChangeListener;
import ru.fix.dynamic.property.api.DynamicPropertySource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Implementation of {@link DynamicPropertyChangeListener} which uses {@link TreeCache} inside and
 * provides subscriptions to property change events
 */
public class ZkPropertySource implements DynamicPropertySource {

    private static final Logger log = LoggerFactory.getLogger(ZkPropertySource.class);

    private static final int UPSERT_PROPERTY_RETRY_COUNT = 10;

    private final String configLocation;
    private CuratorFramework curatorFramework;

    private Map<String, Collection<DynamicPropertyChangeListener<String>>> listeners = new ConcurrentHashMap<>();

    private TreeCache treeCache;

    public ZkPropertySource(String zookeeperQuorum, String configLocation) throws Exception {
        this(CuratorFrameworkFactory.newClient(zookeeperQuorum, new ExponentialBackoffRetry(1000, 10)), configLocation);
    }

    /**
     * @param curatorFramework Ready to use curator framework
     * @param configLocation   Root path where ZkPropertySource will store properties. E.g.
     *                         '/cpapsm/config/SWS'
     */
    public ZkPropertySource(CuratorFramework curatorFramework, String configLocation) throws Exception {
        this.curatorFramework = curatorFramework;
        this.configLocation = configLocation;
        init();
    }

    private void init() throws Exception {
        if (curatorFramework.getState().equals(CuratorFrameworkState.LATENT)) {
            curatorFramework.start();
        }

        treeCache = new TreeCache(this.curatorFramework, this.configLocation);
        treeCache.getListenable().addListener((currentFramework, treeCacheEvent) -> {
            switch (treeCacheEvent.getType()) {
                case NODE_ADDED:
                case NODE_UPDATED:
                    firePropertyChanged(treeCacheEvent, path -> {
                        try {
                            return new String(currentFramework.getData().forPath(treeCacheEvent.getData().getPath()),
                                    StandardCharsets.UTF_8);
                        } catch (Exception e) {
                            log.error("Zk property updating error", e);
                        }
                        return null;
                    });
                    break;
                case NODE_REMOVED:
                    firePropertyChanged(treeCacheEvent, path -> null);
                    break;
                default:
                    break;
            }
        });
        treeCache.start();
    }

    private void firePropertyChanged(TreeCacheEvent treeCacheEvent, Function<String, String> valueExtractor) {
        String propertyPath = treeCacheEvent.getData().getPath();
        Collection<DynamicPropertyChangeListener<String>> zkPropertyChangeListeners = listeners.get(propertyPath);
        String newValue = valueExtractor.apply(propertyPath);
        log.info("Event type {} for node '{}'. New value is '{}'", treeCacheEvent.getType(), propertyPath, newValue);

        if (zkPropertyChangeListeners != null) {
            zkPropertyChangeListeners.forEach(listener -> {
                try {
                    listener.onPropertyChanged(newValue);
                } catch (Exception e) {
                    log.error("Failed to update property {}", propertyPath, e);
                }

            });
        }
    }

    @Override
    public Properties uploadInitialProperties(String defaultPropertiesFileName) throws Exception {
        Properties currentProperties = getAllProperties();

        Properties initialProperties = new Properties();
        InputStream input = ZkPropertySource.class.getClassLoader().getResourceAsStream(defaultPropertiesFileName);
        initialProperties.load(input);

        for (String property : initialProperties.stringPropertyNames()) {
            if (currentProperties.getProperty(property) == null) {
                curatorFramework.create().creatingParentsIfNeeded().forPath(configLocation + "/" + property,
                        initialProperties.getProperty(property).getBytes(StandardCharsets.UTF_8));
                currentProperties.put(property, initialProperties.getProperty(property));
            }
        }
        return currentProperties;
    }

    @Override
    public void upsertProperty(String key, String propVal) throws Exception {
        String propPath = getAbsolutePath(key);
        int iteration = 0;
        do {
            ChildData currentData = treeCache.getCurrentData(propPath);
            byte[] newData = propVal.getBytes(StandardCharsets.UTF_8);
            if (currentData != null) {
                if (!Arrays.equals(currentData.getData(), newData)) {
                    curatorFramework.setData().forPath(propPath, newData);
                }
                break;
            }

            try {
                curatorFramework.create().creatingParentsIfNeeded().forPath(propPath, newData);
            } catch (KeeperException.NodeExistsException e) {
                iteration++;
                log.debug("upserting property '{}'='{}', iteration {}", propPath, propVal, iteration);
                if (iteration < UPSERT_PROPERTY_RETRY_COUNT) {
                    continue;
                }

                throw e;
            }
            break;
        } while (true);
    }

    @Override
    public void putIfAbsent(String key, String propVal) throws Exception {
        String propPath = getAbsolutePath(key);
        ChildData currentData = treeCache.getCurrentData(propPath);
        if (currentData == null) {
            try {
                curatorFramework.create()
                        .creatingParentsIfNeeded()
                        .forPath(propPath, propVal.getBytes(StandardCharsets.UTF_8));
            } catch (KeeperException.NodeExistsException e) { //NOSONAR
                // somebody already have created the node, nothing to do
            }
        }
    }

    @Override
    public String getProperty(String key) {
        return getProperty(key, (String) null);
    }

    @Override
    public String getProperty(String key, String defaulValue) {
        String path = getAbsolutePath(key);
        ChildData currentData = treeCache.getCurrentData(path);
        return currentData == null
                ? defaulValue
                : new String(currentData.getData(), StandardCharsets.UTF_8);
    }

    @Override
    public <T> T getProperty(String key, Class<T> type) {
        return getProperty(key, type, null);
    }

    @Override
    public <T> T getProperty(String key, Class<T> type, T defaultValue) {
        String value = getProperty(key);
        return ValueConverter.convert(type, value, defaultValue);
    }

    /**
     * Works through curator directly to load latest data
     */
    @Override
    public Properties getAllProperties() throws Exception {
        Properties allProperties = new Properties();
        Stat exist = curatorFramework.checkExists().forPath(getAbsolutePath(""));
        if (exist != null) {
            List<String> childs = curatorFramework.getChildren().forPath(getAbsolutePath(""));
            if (!childs.isEmpty()) {
                CountDownLatch latcher = new CountDownLatch(childs.size());
                for (String child : childs) {
                    curatorFramework.getData().watched().inBackground((client, event) -> {
                        allProperties.put(child, new String(event.getData(), StandardCharsets.UTF_8));
                        latcher.countDown();
                    }).forPath(getAbsolutePath(child));
                }
                if (!latcher.await(120, TimeUnit.SECONDS)) {
                    throw new TimeoutException("Failed to extract zk properties data");
                }
            }
        }
        return allProperties;
    }

    /**
     * Works through curator directly to load latest data
     */
    @Override
    @SuppressWarnings("squid:S1166") // Either log or rethrow this exception.
    public Map<String, String> getAllSubtreeProperties(String root) throws Exception {
        Map<String, String> allProperties = new ConcurrentHashMap<>();
        Stat exist = curatorFramework.checkExists().forPath(getAbsolutePath(root));
        if (exist != null) {
            getProperty(allProperties, null == root ? "" : root);
        }
        return allProperties;
    }

    private void getProperty(Map<String, String> allProperties, String path) {
        Map<String, ChildData> currentChildren = treeCache.getCurrentChildren(getAbsolutePath(path));
        if (currentChildren != null) {
            currentChildren.forEach((s, childData) -> {
                String currNodePath = path.isEmpty() ? s : path + '/' + s;
                allProperties.put(currNodePath, new String(childData.getData(), StandardCharsets.UTF_8));
                getProperty(allProperties, currNodePath);
            });
        }
    }

    @Override
    public void updateProperty(String key, String value) throws Exception {
        String path = getAbsolutePath(key);
        curatorFramework.setData().forPath(path, value.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public <T> void addPropertyChangeListener(String propertyName, Class<T> type,
                                              DynamicPropertyChangeListener<T> typedListener) {
        addPropertyChangeListener(propertyName, value -> {
            T convertedValue = ValueConverter.convert(type, value, null);
            typedListener.onPropertyChanged(convertedValue);
        });
    }

    @Override
    public void addPropertyChangeListener(String propertyName, DynamicPropertyChangeListener<String> listener) {
        listeners.computeIfAbsent(getAbsolutePath(propertyName), key -> new CopyOnWriteArrayList<>()).add(listener);
    }

    private String getAbsolutePath(String nodeName) {
        boolean nodeNameIsEmpty = null == nodeName || nodeName.isEmpty();
        return configLocation + (nodeNameIsEmpty ? "" : '/' + nodeName);
    }

    @Override
    public void close() throws Exception {
        treeCache.close();
    }

}
