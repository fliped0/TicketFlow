package com.ticketflow.identity;
import org.apache.ibatis.annotations.*;
@Mapper
public interface UserMapper {
 @Select("SELECT id, username, password_hash, role, enabled FROM tf_user WHERE username=#{username}")
 UserAccount byUsername(String username);
 @Select("SELECT id, username, password_hash, role, enabled FROM tf_user WHERE id=#{id}")
 UserAccount byId(long id);
 @Insert("INSERT INTO tf_user(username,password_hash,role,enabled,created_at) VALUES(#{username},#{passwordHash},#{role},TRUE,UTC_TIMESTAMP(6))")
 int insert(@Param("username") String username, @Param("passwordHash") String passwordHash, @Param("role") String role);
}
