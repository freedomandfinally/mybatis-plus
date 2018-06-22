package com.github.mustfun.mybatis.plugin.service;

import com.github.mustfun.mybatis.plugin.annotation.Annotation;
import com.github.mustfun.mybatis.plugin.model.DbSourcePo;
import com.github.mustfun.mybatis.plugin.model.LocalColumn;
import com.github.mustfun.mybatis.plugin.model.LocalTable;
import com.github.mustfun.mybatis.plugin.model.PluginConfig;
import com.github.mustfun.mybatis.plugin.model.enums.VmTypeEnums;
import com.github.mustfun.mybatis.plugin.provider.FileProviderFactory;
import com.github.mustfun.mybatis.plugin.setting.ConnectDbSetting;
import com.github.mustfun.mybatis.plugin.util.DbUtil;
import com.github.mustfun.mybatis.plugin.util.JavaUtils;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.WordUtils;
import org.apache.tools.ant.util.DateUtils;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.StringResourceLoader;
import org.apache.velocity.runtime.resource.util.StringResourceRepository;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


/**
 * @author dengzhiyuan
 * @version 1.0
 * @date 2018/6/13
 * @since 1.0
 */
public class DbService {

    public final static String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    private static ConcurrentHashMap<Integer, Integer> templateGenerateTimeMap = new ConcurrentHashMap<>(10);
    /**
     * 存放生成的file文件的
     */
    private static ConcurrentHashMap<Integer, PsiFile> fileHashMap = new ConcurrentHashMap<>(10);

    private static Project project;

    public static DbService getInstance(@NotNull Project project) {
        DbService.project = project;
        return ServiceManager.getService(project, DbService.class);
    }

    public  Connection getConnection(DbSourcePo configPo) {
        DbUtil dbUtil = new DbUtil(configPo.getDbAddress(), configPo.getDbName(), configPo.getUserName(), configPo.getPassword());
        return dbUtil.getConnection();
    }

    public  Connection getSqlLiteConnection() {
        DbUtil dbUtil = new DbUtil();
        return dbUtil.getSqlliteConnection();
    }

    public List<LocalTable> getTables(Connection connection) {
        DatabaseMetaData dbMetData;
        List<LocalTable> localTables = new ArrayList<>();
        try {
            dbMetData = connection.getMetaData();
            String[] types = {"TABLE"};
            ResultSet rs = dbMetData.getTables(null, null, "%", types);
            while (rs.next()) {
                LocalTable localTable = initLocalTable(connection, rs);
                localTables.add(localTable);
            }
        } catch (SQLException e) {
            System.out.println("table出错 e = " + e);
        }
        return localTables;
    }

    public LocalTable initLocalTable(Connection connection, ResultSet rs) throws SQLException {
        LocalTable localTable = new LocalTable();
        String tableName = rs.getString("TABLE_NAME");
        System.out.println("tableName = " + tableName);
        String tableType = rs.getString("TABLE_TYPE");
        String remarks = rs.getString("REMARKS");
        localTable.setComment(remarks);
        localTable.setTableType(tableType);
        localTable.setTableName(tableName);
        getColumns(connection.getMetaData(),tableName,localTable);
        return localTable;
    }

    private LocalTable getColumns(DatabaseMetaData meta, String tableName,LocalTable localTable) throws SQLException {
        List<LocalColumn> localColumns = new ArrayList<>();
        ResultSet primaryKeys = meta.getPrimaryKeys(null, null, tableName);
        String pkColumnName = null;
        while (primaryKeys.next()) {
            pkColumnName = primaryKeys.getString("COLUMN_NAME");
        }
        LocalColumn pkColumn = new LocalColumn();
        ResultSet survey = meta.getColumns(null, null, tableName, null);
        while (survey.next()) {
            LocalColumn localColumn = new LocalColumn();
            String columnName = survey.getString("COLUMN_NAME");
            localColumn.setColumnName(columnName);
            String columnType = survey.getString("TYPE_NAME");
            localColumn.setDataType(columnType);
            int size = survey.getInt("COLUMN_SIZE");
            localColumn.setSize(size);
            int nullable = survey.getInt("NULLABLE");
            if (nullable == DatabaseMetaData.columnNullable) {
                localColumn.setNullable(true);
            } else {
                localColumn.setNullable(false);
            }
            int position = survey.getInt("ORDINAL_POSITION");
            localColumn.setPosition(position);
            localColumn.setColumnComment(survey.getString("REMARKS"));
            if (columnName.equalsIgnoreCase(pkColumnName)){
                pkColumn=localColumn;
            }
            localColumns.add(localColumn);
        }
        localTable.setPk(pkColumn);
        localTable.setTableName(tableName);
        localTable.setColumnList(localColumns);
        return localTable;
    }


    public void generateCodeUseTemplate(ConnectDbSetting connectDbSetting, Connection connection, LocalTable columns, String packageName, List<Integer> vmList) {
        generatorCode(connectDbSetting,connection,columns,columns.getColumnList(),packageName,vmList);
    }


    public static void generatorCode(ConnectDbSetting connectDbSetting, Connection connection, LocalTable table,
                                     List<LocalColumn> columns, String packageName, List<Integer> vmList) {

        SqlLiteService sqlLiteService =  SqlLiteService.getInstance(connection);

        boolean hasBigDecimal = false;
        //表名转换成Java类名
        PluginConfig tablePrefix = sqlLiteService.queryPluginConfigByKey("tablePrefix");
        String className = tableToJava(table.getTableName(), tablePrefix==null?"t":tablePrefix.getValue());
        table.setClassName(className);
        table.setClassLittleName(StringUtils.uncapitalize(className));

        //列信息
        List<LocalColumn> columnsList = new ArrayList<>();
        for(LocalColumn column : columns){

            //列名转换成Java属性名
            String attrName = columnToJava(column.getColumnName());
            column.setAttrName(attrName);
            column.setAttrLittleName(StringUtils.uncapitalize(attrName));

            //列的数据类型，转换成Java类型
            String attrType = sqlLiteService.queryPluginConfigByKey(column.getDataType().toUpperCase()).getValue();
            column.setAttrType(attrType);
            if (!hasBigDecimal && attrType.equals("BigDecimal" )) {
                hasBigDecimal = true;
            }
            //BIGINT处理一下
            if (column.getDataType().toUpperCase().equalsIgnoreCase("BITINT UNSIGNED")){
                column.setDataType("BIGINT");
            }
            columnsList.add(column);
        }
        table.setColumnList(columnsList);

        //没主键，则第一个字段为主键
        if (table.getPk() == null) {
            table.setPk(table.getColumnList().get(0));
        }

        //设置velocity资源加载器
        VelocityEngine engine = new VelocityEngine();
        engine.setProperty(RuntimeConstants.RUNTIME_LOG_LOGSYSTEM_CLASS, "org.apache.velocity.runtime.log.Log4JLogChute");
        engine.setProperty(Velocity.RESOURCE_LOADER, "string");
        engine.addProperty("string.resource.loader.class", StringResourceLoader.class.getName());
        engine.addProperty("string.resource.loader.repository.static", "false");

        engine.init();
        StringResourceRepository repo = (StringResourceRepository) engine.getApplicationAttribute(StringResourceLoader.REPOSITORY_NAME_DEFAULT);

        String mainPath = sqlLiteService.queryPluginConfigByKey("mainPath").getValue();
        mainPath = StringUtils.isBlank(mainPath) ? "com.generator" : mainPath;
        //封装模板数据
        Map<String, Object> map = new HashMap<>();
        map.put("tableName", table.getTableName());
        map.put("comment", table.getComment());
        map.put("pk", table.getPk());
        map.put("className", table.getClassName());
        map.put("classLittleName", table.getClassLittleName());
        map.put("pathName", table.getClassLittleName().toLowerCase());
        map.put("columns", table.getColumnList());
        map.put("hasBigDecimal", hasBigDecimal);
        map.put("mainPath", mainPath);
        map.put("author", sqlLiteService.queryPluginConfigByKey("author").getValue());
        map.put("email", sqlLiteService.queryPluginConfigByKey("email").getValue());
        map.put("datetime", DateUtils.format(new Date(), DbService.DATE_TIME_PATTERN));
        VelocityContext context = new VelocityContext(map);

        //vmList排序
        vmList.sort((o1, o2) -> sqlLiteService.queryTemplateById(o1).getVmType()<sqlLiteService.queryTemplateById(o2).getVmType()?1:0);

        //获取模板列表
        for (Integer templateId : vmList) {
                  //取出模板
            com.github.mustfun.mybatis.plugin.model.Template template = sqlLiteService.queryTemplateById(templateId);
            if(!checkNeedGenerate(template.getVmType())){
                continue;
            }

            //渲染模板
            try (StringWriter sw = new StringWriter()) {

                String fileName = getFileName(template.getVmType(), table.getClassName(), packageName);
                String outPath = getRealPath(template.getVmType(),connectDbSetting);


                VirtualFile vFile = VfsUtil.createDirectoryIfMissing(outPath);
                PsiDirectory directory = PsiManager.getInstance(project).findDirectory(vFile);
                String packageName1 = JavaUtils.getPackageName(directory, templateId);


                //merge 操作
                repo.putStringResource(template.getTepName()+"_"+template.getId(), template.getTepContent());
                Template tpl = engine.getTemplate(template.getTepName()+"_"+template.getId(),"UTF-8");
                tpl.merge(context, sw);


                FileProviderFactory fileFactory = new FileProviderFactory(project,outPath);
                PsiFile psiFile;
                if (template.getVmType().equals(VmTypeEnums.MAPPER.getCode())) {
                    psiFile = fileFactory.getInstance("xml").create(sw.toString(), fileName);
                }else {
                    psiFile = fileFactory.getInstance("java").create(sw.toString(), fileName);
                }
                fileHashMap.put(template.getVmType(), psiFile);
                if (psiFile!=null&&psiFile instanceof PsiJavaFile){
                    importNeedClass(psiFile,template.getVmType());
                }

            } catch (IOException e) {
                System.out.println("渲染模板发生异常{}e = " + e);
                throw new RuntimeException("渲染模板失败，表名：" + table.getTableName(), e);
            }
        }
    }



    private static void importNeedClass(PsiFile psiFile,Integer vmType){
        if (vmType.equals(VmTypeEnums.SERVICE.getCode())) {

        }
        if (vmType.equals(VmTypeEnums.DAO.getCode())) {
            //dao层引入model
            JavaService.getInstance(project).importClazz((PsiJavaFile) psiFile, fileHashMap.get(VmTypeEnums.MODEL_PO.getCode()).getName());
        }
    }

    private static String getRealPath(Integer template, ConnectDbSetting connectDbSetting) {
        if (template.equals(VmTypeEnums.RESULT.getCode())) {
            return  connectDbSetting.getPoInput().getText();
        }
        if (template.equals(VmTypeEnums.MODEL_PO.getCode())) {
            return  connectDbSetting.getPoInput().getText()+"/po";
        }

        if (template.equals(VmTypeEnums.MODEL_BO.getCode())) {
            return  connectDbSetting.getPoInput().getText()+"/bo";
        }

        if (template.equals(VmTypeEnums.MODEL_REQ.getCode())) {
            return  connectDbSetting.getPoInput().getText()+"/req";
        }

        if (template.equals(VmTypeEnums.MODEL_RESP.getCode())) {
            return  connectDbSetting.getPoInput().getText()+"/resp";
        }
        if (template.equals(VmTypeEnums.DAO.getCode())) {
            return  connectDbSetting.getDaoInput().getText();
        }

        if (template.equals(VmTypeEnums.SERVICE.getCode())) {
            return  connectDbSetting.getServiceInput().getText();
        }

        if (template.equals(VmTypeEnums.SERVICE_IMPL.getCode())) {
            return  connectDbSetting.getServiceInput().getText()+"/impl";
        }

        if (template.equals(VmTypeEnums.CONTROLLER.getCode())) {
            return  connectDbSetting.getPoInput().getText();
        }

        if (template.equals(VmTypeEnums.MAPPER.getCode())) {
            return  connectDbSetting.getMapperInput().getText();
        }
        return null;
    }

    /**
     * 表名转换成Java类名
     */
    public static String tableToJava(String tableName, String tablePrefix) {
        if (StringUtils.isNotBlank(tablePrefix)) {
            tableName = tableName.replaceFirst(tablePrefix, "" );
        }
        return columnToJava(tableName);
    }

    /**
     * 列名转换成Java属性名
     */
    public static String columnToJava(String columnName) {
        return WordUtils.capitalizeFully(columnName, new char[]{'_'}).replace("_", "" );
    }

    private  static boolean checkNeedGenerate(Integer template) {
        if (template.equals(VmTypeEnums.RESULT.getCode())){
            Integer integer = templateGenerateTimeMap.get(template);
            if (integer==null){
                templateGenerateTimeMap.put(template,1);
            }else if(integer>0){
                return false;
            }
        }
        return true;
    }


    /**
     * 获取文件名
     */
    public static String getFileName(Integer template, String className, String packageName) {
        if (template.equals(VmTypeEnums.RESULT.getCode())) {
            return "Result.java";
        }
        if (template.equals(VmTypeEnums.MODEL_PO.getCode())) {
            return className + "Po.java";
        }
        if (template.equals(VmTypeEnums.MODEL_BO.getCode())) {
            return className + "Bo.java";
        }
        if (template.equals(VmTypeEnums.MODEL_REQ.getCode())) {
            return className + "Req.java";
        }
        if (template.equals(VmTypeEnums.MODEL_RESP.getCode())) {
            return className + "Resp.java";
        }
        if (template.equals(VmTypeEnums.DAO.getCode())) {
            return className + "Dao.java";
        }
        if (template.equals(VmTypeEnums.SERVICE.getCode())) {
            return className + "Service.java";
        }
        if (template.equals(VmTypeEnums.SERVICE_IMPL.getCode())) {
            return className + "ServiceImpl.java";
        }
        if (template.equals(VmTypeEnums.CONTROLLER.getCode())) {
            return className + "Controller.java";
        }
        if (template.equals(VmTypeEnums.MAPPER.getCode())) {
            return className + "Dao.xml";
        }
        return null;
    }


}
