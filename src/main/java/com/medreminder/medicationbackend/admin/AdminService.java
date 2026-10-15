package com.medreminder.medicationbackend.admin;

import com.medreminder.medicationbackend.common.UserContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class AdminService {
    private final JdbcTemplate db;
    private final ConsoleSettings settings;
    public AdminService(JdbcTemplate db,ConsoleSettings settings) { this.db=db; this.settings=settings; }
    public record Page(List<Map<String,Object>> items,long total,int page,int pageSize) {}
    private static final String USER_COLUMNS="id,account,nickname,role,status,create_time as createTime,last_login_at as lastLoginAt";
    public Map<String,Object> me() {
        return db.queryForMap("select "+USER_COLUMNS+" from sys_user where id=? and deleted=0",UserContext.userId());
    }
    public Map<String,Long> overview() {
        return Map.of("totalUsers",count("sys_user"," where deleted=0"),
                "activeUsers",count("sys_user"," where deleted=0 and status=1"),
                "openEvents",count("abnormal_event"," where status<>'CLOSED'"),
                "totalTasks",count("remind_task",""),
                "pendingNotifications",count("notification"," where send_status='PENDING'"),
                "failedNotifications",count("notification"," where send_status='FAILED'"));
    }
    private long count(String table,String where) { return db.queryForObject("select count(*) from "+table+where,Long.class); }
    public Page users(String keyword,Integer status,int page,int pageSize) {
        var args=new ArrayList<Object>(); String where=" where deleted=0";
        if(keyword!=null && !keyword.isBlank()) {
            if(keyword.length()>80) throw new IllegalArgumentException("搜索词至多80字符");
            where+=" and (locate(?,account)>0 or locate(?,nickname)>0)"; args.add(keyword.trim()); args.add(keyword.trim());
        }
        if(status!=null) { if(status!=0 && status!=1) throw new IllegalArgumentException("无效账号状态"); where+=" and status=?"; args.add(status); }
        return page("sys_user",USER_COLUMNS,where,args,page,pageSize);
    }
    public List<Map<String,Object>> settings(String category) {
        if(category!=null && !Set.of("DICT","TEMPLATE","PARAM").contains(category)) throw new IllegalArgumentException("无效配置类别");
        String sql="select id,category,code,name,setting_value as value,description,version,update_time as updateTime from admin_setting";
        return category==null?db.queryForList(sql+" order by id"):db.queryForList(sql+" where category=? order by id",category);
    }
    public Page events(String status,int page,int pageSize) {
        return byStatus("abnormal_event","id,type,status,occurred_at as occurredAt,notified_at as notifiedAt,acknowledged_at as acknowledgedAt,closed_at as closedAt",
                "status",status,Set.of("OPEN","NOTIFIED","ACKNOWLEDGED","CLOSED"),page,pageSize);
    }
    public Page notifications(String status,int page,int pageSize) {
        return byStatus("notification","id,type,biz_type as bizType,send_status as sendStatus,retry_count as retryCount,create_time as createTime,sent_at as sentAt",
                "send_status",status,Set.of("PENDING","SENT","FAILED","CANCELLED"),page,pageSize);
    }
    public Page audits(String action,int page,int pageSize) {
        return byStatus("admin_audit_log","id,operator_id as operatorId,action,target_type as targetType,target_id as targetId,reason,before_value as beforeValue,after_value as afterValue,create_time as createTime",
                "action",action,Set.of("USER_STATUS","SETTING_UPDATE"),page,pageSize);
    }
    private Page byStatus(String table,String columns,String field,String status,Set<String> allowed,int page,int size) {
        if(status==null || status.isBlank()) return page(table,columns,"",List.of(),page,size);
        if(!allowed.contains(status)) throw new IllegalArgumentException("无效筛选状态");
        return page(table,columns," where "+field+"=?",List.of(status),page,size);
    }
    private Page page(String table,String columns,String where,List<Object> args,int page,int size) {
        if(page<1 || page>100000 || size<1 || size>50) throw new IllegalArgumentException("页码1至100000，每页数量1至50");
        long total=db.queryForObject("select count(*) from "+table+where,Long.class,args.toArray());
        var paged=new ArrayList<>(args); paged.add(size); paged.add((page-1)*size);
        return new Page(db.queryForList("select "+columns+" from "+table+where+" order by id desc limit ? offset ?",paged.toArray()),total,page,size);
    }
    /** The state write and append-only audit share one transaction. No role or password changes. */
    @Transactional
    public int status(Long id,int status,String reason) {
        reason=reason(reason);
        if(status!=0 && status!=1) throw new IllegalArgumentException("账号状态必须为0或1");
        var rows=db.queryForList("select role,status from sys_user where id=? and deleted=0 for update",id);
        if(rows.isEmpty()) return 404;
        var row=rows.get(0);
        if(!"USER".equals(row.get("role"))) return 409;
        if(((Number)row.get("status")).intValue()==status) return 200;
        db.update("update sys_user set status=?,update_time=now() where id=? and deleted=0",status,id);
        audit("USER_STATUS","USER",id,reason,row.get("status").toString(),Integer.toString(status));
        return 200;
    }
    @Transactional
    public int updateSetting(Long id,String value,int version,String reason) {
        reason=reason(reason);
        var rows=db.queryForList("select code,setting_value,version from admin_setting where id=? for update",id);
        if(rows.isEmpty()) return 404;
        var row=rows.get(0);
        if(((Number)row.get("version")).intValue()!=version) return 409;
        value=settings.validate((String)row.get("code"),value);
        db.update("update admin_setting set setting_value=?,version=version+1,update_time=now() where id=? and version=?",value,id,version);
        audit("SETTING_UPDATE","SETTING",id,reason,(String)row.get("setting_value"),value);
        return 200;
    }
    private String reason(String reason) {
        if(reason==null || reason.trim().length()<3 || reason.trim().length()>200) throw new IllegalArgumentException("请填写3至200字的操作原因");
        return reason.trim();
    }
    private void audit(String action,String type,Long id,String reason,String before,String after) {
        db.update("insert into admin_audit_log(operator_id,action,target_type,target_id,reason,before_value,after_value) values(?,?,?,?,?,?,?)",
                UserContext.userId(),action,type,id.toString(),reason,before,after);
    }
}
