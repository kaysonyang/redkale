/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.source;

import com.sun.istack.internal.logging.Logger;
import java.io.Serializable;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;
import java.util.logging.*;
import javax.persistence.*;
import org.redkale.util.*;

/**
 *
 * @see http://www.redkale.org
 * @author zhangjx
 * @param <T>
 */
@SuppressWarnings("unchecked")
public final class EntityInfo<T> {

    private static final ConcurrentHashMap<Class, EntityInfo> entityInfos = new ConcurrentHashMap<>();

    private static final Logger logger = Logger.getLogger(EntityInfo.class);

    //Entity类的类名
    private final Class<T> type;

    //类对应的数据表名, 如果是VirtualEntity 类， 则该字段为null
    private final String table;

    private final Creator<T> creator;

    //主键
    final Attribute<T, Serializable> primary;

    private final EntityCache<T> cache;

    //key是field的name， 不是sql字段。
    //存放所有与数据库对应的字段， 包括主键
    private final HashMap<String, Attribute<T, Serializable>> attributeMap = new HashMap<>();

    final Attribute<T, Serializable>[] attributes;

    //key是field的name， value是Column的别名，即数据库表的字段名
    //只有field.name 与 Column.name不同才存放在aliasmap里.
    private final Map<String, String> aliasmap;

    private final Map<String, Attribute<T, Serializable>> updateAttributeMap = new HashMap<>();

    final String querySQL;

    private final Attribute<T, Serializable>[] queryAttributes; //数据库中所有字段

    final String insertSQL;

    final Attribute<T, Serializable>[] insertAttributes; //数据库中所有可新增字段

    final String updateSQL;

    final Attribute<T, Serializable>[] updateAttributes; //数据库中所有可更新字段

    final String deleteSQL;

    private final int logLevel;

    private final Map<String, String> sortOrderbySqls = new ConcurrentHashMap<>();

    //---------------------计算主键值----------------------------
    private final int nodeid;

    final Class[] distributeTables;

    final boolean autoGenerated;

    final boolean distributed;

    boolean initedPrimaryValue = false;

    final AtomicLong primaryValue = new AtomicLong(0);

    final int allocationSize;
    //------------------------------------------------------------

    public static <T> EntityInfo<T> load(Class<T> clazz, final int nodeid, final boolean cacheForbidden,
            Function<Class, List> fullloader) {
        EntityInfo rs = entityInfos.get(clazz);
        if (rs != null) return rs;
        synchronized (entityInfos) {
            rs = entityInfos.get(clazz);
            if (rs == null) {
                if (nodeid < 0) throw new IllegalArgumentException("nodeid(" + nodeid + ") is illegal");
                rs = new EntityInfo(clazz, nodeid, cacheForbidden);
                entityInfos.put(clazz, rs);
                AutoLoad auto = clazz.getAnnotation(AutoLoad.class);
                if (rs.cache != null && auto != null && auto.value()) {
                    if (fullloader == null) throw new IllegalArgumentException(clazz.getName() + " auto loader  is illegal");
                    rs.cache.fullLoad(fullloader.apply(clazz));
                }
            }
            return rs;
        }
    }

    static <T> EntityInfo<T> get(Class<T> clazz) {
        return entityInfos.get(clazz);
    }

    private EntityInfo(Class<T> type, int nodeid, final boolean cacheForbidden) {
        this.type = type;
        //---------------------------------------------
        this.nodeid = nodeid >= 0 ? nodeid : 0;
        DistributeTables dt = type.getAnnotation(DistributeTables.class);
        this.distributeTables = dt == null ? null : dt.value();

        LogLevel ll = type.getAnnotation(LogLevel.class);
        this.logLevel = ll == null ? Integer.MIN_VALUE : Level.parse(ll.value()).intValue();
        //---------------------------------------------
        Table t = type.getAnnotation(Table.class);
        if (type.getAnnotation(VirtualEntity.class) != null) {
            this.table = null;
        } else {
            this.table = (t == null) ? type.getSimpleName().toLowerCase() : (t.catalog().isEmpty()) ? t.name() : (t.catalog() + '.' + t.name());
        }
        this.creator = Creator.create(type);
        Attribute idAttr0 = null;
        Map<String, String> aliasmap0 = null;
        Class cltmp = type;
        Set<String> fields = new HashSet<>();
        List<Attribute<T, Serializable>> queryattrs = new ArrayList<>();
        List<String> insertcols = new ArrayList<>();
        List<Attribute<T, Serializable>> insertattrs = new ArrayList<>();
        List<String> updatecols = new ArrayList<>();
        List<Attribute<T, Serializable>> updateattrs = new ArrayList<>();
        boolean auto = false;
        boolean sqldistribute = false;
        int allocationSize0 = 0;

        do {
            for (Field field : cltmp.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (Modifier.isFinal(field.getModifiers())) continue;
                if (field.getAnnotation(Transient.class) != null) continue;
                if (fields.contains(field.getName())) continue;
                final String fieldname = field.getName();
                final Column col = field.getAnnotation(Column.class);
                final String sqlfield = col == null || col.name().isEmpty() ? fieldname : col.name();
                if (!fieldname.equals(sqlfield)) {
                    if (aliasmap0 == null) aliasmap0 = new HashMap<>();
                    aliasmap0.put(fieldname, sqlfield);
                }
                Attribute attr;
                try {
                    attr = Attribute.create(cltmp, field);
                } catch (RuntimeException e) {
                    continue;
                }
                if (field.getAnnotation(javax.persistence.Id.class) != null && idAttr0 == null) {
                    idAttr0 = attr;
                    GeneratedValue gv = field.getAnnotation(GeneratedValue.class);
                    auto = gv != null;
//                    if (gv != null && gv.strategy() != GenerationType.IDENTITY) {
//                        throw new RuntimeException(cltmp.getName() + "'s @ID primary not a GenerationType.IDENTITY");
//                    }
                    DistributeGenerator dg = field.getAnnotation(DistributeGenerator.class);
                    if (dg != null) {
                        if (!field.getType().isPrimitive()) throw new RuntimeException(cltmp.getName() + "'s @" + DistributeGenerator.class.getSimpleName() + " primary must be primitive class type field");
                        sqldistribute = true;
                        auto = false;
                        allocationSize0 = dg.allocationSize();
                        primaryValue.set(dg.initialValue());
                    }
                    if (!auto) {
                        insertcols.add(sqlfield);
                        insertattrs.add(attr);
                    }
                } else {
                    if (col == null || col.insertable()) {
                        insertcols.add(sqlfield);
                        insertattrs.add(attr);
                    }
                    if (col == null || col.updatable()) {
                        updatecols.add(sqlfield);
                        updateattrs.add(attr);
                        updateAttributeMap.put(fieldname, attr);
                    }
                }
                queryattrs.add(attr);
                fields.add(fieldname);
                attributeMap.put(fieldname, attr);
            }
        } while ((cltmp = cltmp.getSuperclass()) != Object.class);
        this.primary = idAttr0;
        this.aliasmap = aliasmap0;
        this.attributes = attributeMap.values().toArray(new Attribute[attributeMap.size()]);
        this.queryAttributes = queryattrs.toArray(new Attribute[queryattrs.size()]);
        this.insertAttributes = insertattrs.toArray(new Attribute[insertattrs.size()]);
        this.updateAttributes = updateattrs.toArray(new Attribute[updateattrs.size()]);
        if (table != null) {
            StringBuilder insertsb = new StringBuilder();
            StringBuilder insertsb2 = new StringBuilder();
            for (String col : insertcols) {
                if (insertsb.length() > 0) insertsb.append(',');
                insertsb.append(col);
                if (insertsb2.length() > 0) insertsb2.append(',');
                insertsb2.append('?');
            }
            this.insertSQL = "INSERT INTO " + table + "(" + insertsb + ") VALUES(" + insertsb2 + ")";
            StringBuilder updatesb = new StringBuilder();
            for (String col : updatecols) {
                if (updatesb.length() > 0) updatesb.append(", ");
                updatesb.append(col).append(" = ?");
            }
            this.updateSQL = "UPDATE " + table + " SET " + updatesb + " WHERE " + getPrimarySQLColumn(null) + " = ?";
            this.deleteSQL = "DELETE FROM " + table + " WHERE " + getPrimarySQLColumn(null) + " = ?";
            this.querySQL = "SELECT * FROM " + table + " WHERE " + getPrimarySQLColumn(null) + " = ?";
        } else {
            this.insertSQL = null;
            this.updateSQL = null;
            this.deleteSQL = null;
            this.querySQL = null;
        }
        this.autoGenerated = auto;
        this.distributed = sqldistribute;
        this.allocationSize = allocationSize0;
        //----------------cache--------------
        Cacheable c = type.getAnnotation(Cacheable.class);
        if (this.table == null || (!cacheForbidden && c != null && c.value())) {
            this.cache = new EntityCache<>(this);
        } else {
            this.cache = null;
        }
    }

    public void createPrimaryValue(T src) {
        long v = allocationSize > 1 ? (primaryValue.incrementAndGet() * allocationSize + nodeid) : primaryValue.incrementAndGet();
        if (primary.type() == int.class || primary.type() == Integer.class) {
            getPrimary().set(src, (Integer) ((Long) v).intValue());
        } else {
            getPrimary().set(src, v);
        }
    }

    public EntityCache<T> getCache() {
        return cache;
    }

    public boolean isCacheFullLoaded() {
        return cache != null && cache.isFullLoaded();
    }

    public Creator<T> getCreator() {
        return creator;
    }

    public Class<T> getType() {
        return type;
    }

    /**
     * 是否虚拟类
     * <p>
     * @return
     */
    public boolean isVirtualEntity() {
        return table == null;
    }

    public String getTable() {
        return table;
    }

    public Attribute<T, Serializable> getPrimary() {
        return this.primary;
    }

    public void forEachAttribute(BiConsumer<String, Attribute<T, Serializable>> action) {
        this.attributeMap.forEach(action);
    }

    public Attribute<T, Serializable> getAttribute(String fieldname) {
        if (fieldname == null) return null;
        return this.attributeMap.get(fieldname);
    }

    public Attribute<T, Serializable> getUpdateAttribute(String fieldname) {
        return this.updateAttributeMap.get(fieldname);
    }

    public boolean isNoAlias() {
        return this.aliasmap == null;
    }

    protected String createSQLOrderby(Flipper flipper) {
        if (flipper == null || flipper.getSort() == null || flipper.getSort().isEmpty()) return "";
        final String sort = flipper.getSort();
        String sql = this.sortOrderbySqls.get(sort);
        if (sql != null) return sql;
        final StringBuilder sb = new StringBuilder();
        sb.append(" ORDER BY ");
        if (isNoAlias()) {
            sb.append(sort);
        } else {
            boolean flag = false;
            for (String item : sort.split(",")) {
                if (item.isEmpty()) continue;
                String[] sub = item.split("\\s+");
                if (flag) sb.append(',');
                if (sub.length < 2 || sub[1].equalsIgnoreCase("ASC")) {
                    sb.append(getSQLColumn("a", sub[0])).append(" ASC");
                } else {
                    sb.append(getSQLColumn("a", sub[0])).append(" DESC");
                }
                flag = true;
            }
        }
        sql = sb.toString();
        this.sortOrderbySqls.put(sort, sql);
        return sql;
    }

    //根据field字段名获取数据库对应的字段名
    public String getSQLColumn(String tabalis, String fieldname) {
        return this.aliasmap == null ? (tabalis == null ? fieldname : (tabalis + '.' + fieldname))
                : (tabalis == null ? aliasmap.getOrDefault(fieldname, fieldname) : (tabalis + '.' + aliasmap.getOrDefault(fieldname, fieldname)));
    }

    public String getPrimarySQLColumn() {
        return getSQLColumn(null, this.primary.field());
    }

    //数据库字段名
    public String getPrimarySQLColumn(String tabalis) {
        return getSQLColumn(tabalis, this.primary.field());
    }

    protected Map<String, Attribute<T, Serializable>> getAttributes() {
        return attributeMap;
    }

    public boolean isLoggable(Level l) {
        return l.intValue() >= this.logLevel;
    }

    protected T getValue(final SelectColumn sels, final ResultSet set) throws SQLException {
        T obj = creator.create();
        for (Attribute<T, Serializable> attr : queryAttributes) {
            if (sels == null || sels.test(attr.field())) {
                Serializable o = (Serializable) set.getObject(this.getSQLColumn(null, attr.field()));
                if (o != null) {
                    Class t = attr.type();
                    if (t == short.class) {
                        o = ((Number) o).shortValue();
                    } else if (t == long.class) {
                        o = ((Number) o).longValue();
                    } else if (t == int.class) {
                        o = ((Number) o).intValue();
                    }
                }
                attr.set(obj, o);
            }
        }
        return obj;
    }
}
