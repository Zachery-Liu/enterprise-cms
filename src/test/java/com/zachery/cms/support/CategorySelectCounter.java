package com.zachery.cms.support;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/** Counts SQL sent to JDBC, rather than calls to the tree assembly implementation. */
public class CategorySelectCounter implements BeanPostProcessor {
    private final AtomicInteger count = new AtomicInteger();
    private final java.util.List<String> statements = new java.util.concurrent.CopyOnWriteArrayList<>();
    public void reset() { count.set(0); statements.clear(); }
    public int count() { return count.get(); }
    public java.util.List<String> statements() {return java.util.List.copyOf(statements);}
    @Override public Object postProcessAfterInitialization(Object bean, String name) {
        if (!"dataSource".equals(name) || !(bean instanceof DataSource source)) return bean;
        return new DelegatingDataSource(source) {
            @Override public Connection getConnection() throws SQLException { return track(source.getConnection()); }
            @Override public Connection getConnection(String user, String password) throws SQLException {
                return track(source.getConnection(user, password));
            }
        };
    }
    private Connection track(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql) {
                        String normalized = sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
                        if (normalized.startsWith("select ")) statements.add(normalized);
                        if (normalized.startsWith("select ") && normalized.contains(" from cms_category")) count.incrementAndGet();
                    }
                    try { return method.invoke(connection, args); }
                    catch (InvocationTargetException error) { throw error.getCause(); }
                });
    }
}
