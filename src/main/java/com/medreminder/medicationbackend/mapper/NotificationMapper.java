package com.medreminder.medicationbackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medreminder.medicationbackend.entity.Notification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {
    // 每次读取、计数和标记已读都在 SQL 中重新校验授权；解绑后历史消息也不可访问。
    String AUTHORIZED = "n.receiver_id = #{userId} AND "
            + "(n.biz_type IS NULL OR n.biz_type != 'ABNORMAL_EVENT' OR EXISTS ("
            + "SELECT 1 FROM abnormal_event e WHERE e.id = n.biz_id AND (e.user_id = #{userId} OR EXISTS ("
            + "SELECT 1 FROM family_relation r WHERE r.owner_user_id = e.user_id "
            + "AND r.family_user_id = #{userId} AND r.relation_status = 'ACTIVE' "
            + "AND FIND_IN_SET('ABNORMAL_EVENT', REPLACE(r.scope, ' ', '')) > 0 "
            + "AND NOT r.bind_time > n.create_time))))";

    String VISIBLE = AUTHORIZED + " AND n.send_status = 'SENT'";

    @Select("SELECT COUNT(*) FROM notification n WHERE n.id = #{id} AND " + AUTHORIZED)
    long countAuthorized(@Param("userId") Long userId, @Param("id") Long id);

    @Select({"<script>SELECT n.* FROM notification n WHERE " + VISIBLE,
            "<if test='unreadOnly'> AND n.read_flag = 0 </if>",
            "<if test='beforeId != null'> AND n.id &lt; #{beforeId} </if>",
            "ORDER BY n.id DESC LIMIT #{limit}</script>"})
    List<Notification> selectVisible(@Param("userId") Long userId, @Param("unreadOnly") boolean unreadOnly,
                                     @Param("beforeId") Long beforeId, @Param("limit") int limit);

    @Select("SELECT n.* FROM notification n WHERE n.id = #{id} AND " + VISIBLE)
    Notification selectVisibleById(@Param("userId") Long userId, @Param("id") Long id);

    @Select("SELECT COUNT(*) FROM notification n WHERE n.read_flag = 0 AND " + VISIBLE)
    long countVisibleUnread(@Param("userId") Long userId);

    @Update("UPDATE notification n SET n.read_flag = 1 WHERE n.id = #{id} AND " + VISIBLE)
    int markVisibleRead(@Param("userId") Long userId, @Param("id") Long id);

    @Update("UPDATE notification n SET n.read_flag = 1 WHERE n.read_flag = 0 AND " + VISIBLE)
    int markAllVisibleRead(@Param("userId") Long userId);
}
