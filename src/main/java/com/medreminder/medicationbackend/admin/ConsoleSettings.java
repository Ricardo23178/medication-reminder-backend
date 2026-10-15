package com.medreminder.medicationbackend.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.Set;

/** Fixed configuration keys: no arbitrary SQL, expression, path or executable template. */
@Service
public class ConsoleSettings {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public ConsoleSettings(JdbcTemplate db,ObjectMapper json) { this.db=db; this.json=json; }
    public record MessageTemplate(String title,String content) {}
    public record DeliveryPolicy(boolean paused,int batchSize) {}

    public DeliveryPolicy deliveryPolicy() {
        var values=db.queryForList("select code,setting_value from admin_setting where category='PARAM'");
        boolean paused=false; int size=100;
        for(var row:values) {
            if("delivery.paused".equals(row.get("code"))) paused=Boolean.parseBoolean((String)row.get("setting_value"));
            if("delivery.batch-size".equals(row.get("code"))) size=Integer.parseInt((String)row.get("setting_value"));
        }
        return new DeliveryPolicy(paused,Math.max(1,Math.min(100,size)));
    }
    public MessageTemplate template(boolean family) {
        String value=db.queryForObject("select setting_value from admin_setting where code=?",String.class,
                family?"notification.family":"notification.owner");
        JsonNode node=parse(value);
        return new MessageTemplate(node.get("title").asText(),node.get("content").asText());
    }
    public String validate(String code,String value) {
        if(value==null || value.length()>4000) throw new IllegalArgumentException("配置内容不能为空或超过4000字符");
        value=value.trim();
        switch(code) {
            case "delivery.paused" -> {
                if(!Set.of("true","false").contains(value)) throw new IllegalArgumentException("暂停开关只能为true或false");
            }
            case "delivery.batch-size" -> {
                if(!value.matches("[1-9][0-9]?|100")) throw new IllegalArgumentException("每批数量必须为1至100的整数");
            }
            case "notification.owner", "notification.family" -> {
                JsonNode node=parse(value);
                if(!node.isObject() || node.size()!=2 || !text(node,"title",80) || !text(node,"content",500))
                    throw new IllegalArgumentException("模板必须包含非空title（至多80字）及content（至多500字），不能含其他字段");
            }
            case "event.labels" -> {
                JsonNode node=parse(value);
                var keys=Set.of("UNCONFIRMED_TIMEOUT","CONSECUTIVE_UNCONFIRMED","PLAN_ENDED_UNFINISHED");
                if(!node.isObject() || node.size()!=keys.size() || keys.stream().anyMatch(k->!text(node,k,40)))
                    throw new IllegalArgumentException("字典必须包含三种既有异常类型，每个名称1至40字");
            }
            default -> throw new IllegalArgumentException("该配置键不允许修改");
        }
        return value;
    }
    private boolean text(JsonNode node,String key,int max) {
        return node.has(key) && node.get(key).isTextual() && !node.get(key).asText().isBlank()
                && node.get(key).asText().length()<=max;
    }
    private JsonNode parse(String value) {
        try {
            JsonNode node=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);
            if(node==null) throw new IllegalArgumentException("empty JSON");
            return node;
        }
        catch(Exception e) { throw new IllegalArgumentException("配置内容必须是合法JSON"); }
    }
}
