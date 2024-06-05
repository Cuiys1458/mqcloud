package com.sohu.tv.mq.cloud.dao;

import java.util.Date;
import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.sohu.tv.mq.cloud.bo.ServerInfo;
import com.sohu.tv.mq.cloud.bo.ServerInfoExt;
import com.sohu.tv.mq.cloud.bo.ServerStatus;
import com.sohu.tv.mq.cloud.task.server.data.Server;

/**
 * 服务器状态信息持久化
 * @Description: 
 * @author yongfeigao
 * @date 2018年7月18日
 */
public interface ServerStatusDao {
    
    /**
     * 查询服务器基本信息
     * @param ip
     * @return @ServerInfo
     */
    @Select("select * from server")
    public List<ServerInfo> queryAllServerInfo();
    
    /**
     * 查询服务器目前的状况
     * @param ip
     * @return @ServerInfo
     */
    @Select("select * from server s left join server_stat ss on ss.ip = s.ip and ss.cdate=#{cdate,jdbcType=DATE} and ss.ctime = "
            + "(select max(ctime) from server_stat where ip = s.ip and cdate=#{cdate,jdbcType=DATE})")
    public List<ServerInfoExt> queryAllServer(@Param("cdate") Date date);

    /**
     * 查询服务器基本信息
     * @param ip
     * @return @ServerInfo
     */
    @Select("select * from server where ip=#{ip}")
    public ServerInfo queryServerInfo(@Param("ip") String ip);
    
    /**
     * 保存服务器发行版信息
     * @param ip
     * @param dist from /etc/issue
     */
    @Insert("<script>insert into server(ip,dist"
            + "<if test=\"type >= 0\">,machine_type</if>"
            + "<if test=\"room != null\">,room</if>"
            + ") "
            + "values (#{ip},#{dist}"
            + "<if test=\"type >= 0\">,#{type}</if>"
            + "<if test=\"room != null\">,#{room}</if>"
            + ") on duplicate key update dist=values(dist)"
            + "<if test=\"type >= 0\">,machine_type=values(machine_type) </if>"
            + "<if test=\"room != null\">,room=values(room) </if>"
            + "</script>")
    public void saveServerInfo(@Param("ip") String ip, @Param("dist") String dist, @Param("type") int type, @Param("room") String room);
    
    /**
     * 删除服务器信息
     * @param ip
     * @return 删除的数量
     */
    @Update("delete from server where ip=#{ip}")
    public Integer deleteServerInfo(@Param("ip") String ip);
    
    /**
     * 保存/更新服务器信息
     * @param server
     * @return 影响的行数
     */
    @Update("insert into server (ip,host,nmon,cpus,cpu_model,kernel,ulimit) values "
            + "(#{server.ip},#{server.host},#{server.nmon},#{server.cpus},#{server.cpuModel},#{server.kernel},#{server.ulimit}) "
            + "on duplicate key update host=values(host), nmon=values(nmon), cpus=values(cpus), "
            + "cpu_model=values(cpu_model), kernel=values(kernel), ulimit=values(ulimit)")
    public Integer saveAndUpdateServerInfo(@Param("server")Server server);
	
	/**
	 * 查询服务器状态
	 * @param ip
	 * @param date
	 * @return List<ServerStatus>
	 */
    @Select("select * from server_stat where ip=#{ip} and cdate=#{cdate,jdbcType=DATE}")
	public List<ServerStatus> queryServerStat(@Param("ip") String ip, 
			@Param("cdate") Date date);
	

	/**
	 * 保存服务器状态
	 * @param server
	 */
	@Insert("insert ignore into server_stat(ip,cdate,ctime,cuser,csys,cwio,c_ext," + 
	        "cload1,cload5,cload15," + 
	        "mtotal,mfree,mcache,mbuffer,mswap,mswap_free," + 
	        "nin,nout,nin_ext,nout_ext," + 
	        "tuse,torphan,twait," + 
	        "dread,dwrite,diops,dbusy,d_ext,dspace)" + 
	        "values(#{server.ip},#{server.collectTime},#{server.time}," + 
	        "#{server.cpu.user},#{server.cpu.sys},#{server.cpu.wait},#{server.cpu.ext}," + 
	        "#{server.load.load1},#{server.load.load5},#{server.load.load15}," + 
	        "#{server.mem.total},#{server.mem.totalFree},#{server.mem.cache}," + 
	        "#{server.mem.buffer},#{server.mem.swap},#{server.mem.swapFree}," + 
	        "#{server.net.nin},#{server.net.nout},#{server.net.ninDetail},#{server.net.noutDetail}," + 
	        "#{server.connection.established},#{server.connection.orphan},#{server.connection.timeWait}," + 
	        "#{server.disk.read},#{server.disk.write},#{server.disk.iops},#{server.disk.busy}," + 
	        "#{server.disk.ext},#{server.disk.space})")
	public void saveServerStat(@Param("server") Server server);

	/**
	 * 保存服务器状态
	 * @param server
	 */
	@Insert("insert into server_stat(ip,cdate,ctime,cuser,csys,cwio,c_ext," +
			"cload1,cload5,cload15," +
			"mtotal,mfree,mcache,mbuffer,mswap,mswap_free," +
			"nin,nout,nin_ext,nout_ext," +
			"tuse,torphan,twait," +
			"dread,dwrite,diops,dbusy,d_ext,dspace)" +
			"values(#{server.ip},#{server.collectTime},#{server.time}," +
			"#{server.cpu.user},#{server.cpu.sys},#{server.cpu.wait},#{server.cpu.ext}," +
			"#{server.load.load1},#{server.load.load5},#{server.load.load15}," +
			"#{server.mem.total},#{server.mem.totalFree},#{server.mem.cache}," +
			"#{server.mem.buffer},#{server.mem.swap},#{server.mem.swapFree}," +
			"#{server.net.nin},#{server.net.nout},#{server.net.ninDetail},#{server.net.noutDetail}," +
			"#{server.connection.established},#{server.connection.orphan},#{server.connection.timeWait}," +
			"#{server.disk.read},#{server.disk.write},#{server.disk.iops},#{server.disk.busy}," +
			"#{server.disk.ext},#{server.disk.space}) on duplicate key update dspace = values(dspace)")
	public void saveAndUpdateServerStat(@Param("server") Server server);
	
	/**
	 * 删除数据
	 * @param date
	 * @return
	 */
	@Delete("delete from server_stat where cdate < #{cdate,jdbcType=DATE}")
	public Integer deleteServerStat(@Param("cdate") Date date);
	
	/**
     * 删除数据
     * @param ip
     * @return
     */
    @Delete("delete from server where ip = #{ip}")
    public Integer deleteServer(@Param("ip") String ip);
    
    /**
     * 修改数据
     * @param ip
     * @param type
     * @return
     */
    @Update("update server set machine_type=#{type} where ip = #{ip}")
    public Integer updateServer(@Param("ip") String ip, @Param("type") int type);
    
    /**
     * 查询服务器状态
     * @param ip
     * @param date
     * @return List<ServerStatus>
     */
    @Select("select * from server_stat where ip=#{ip} and cdate=#{cdate,jdbcType=DATE} and ctime >= #{beginTime}")
    public List<ServerStatus> queryServerStatByIp(@Param("ip") String ip, 
            @Param("cdate") Date date, @Param("beginTime") String beginTime);
}
