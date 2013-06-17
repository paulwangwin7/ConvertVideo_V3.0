package com.njmd.zfms.converter;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * "执法记录仪"管理系统之定时视频格式转换客户端
 * @author 孙强伟
 * @version 0.0.1
 * @since 2013.06.14
 */
public class Client{
	private static Log logger= LogFactory.getLog(Client.class);
	
	private Thread shutdownHook=null;
	private ScheduledExecutorService  executor =null;
	private ConverterThread t=null;
	
	private static final String JDBC_DRIVERNAME="jdbc.driverName";
	private static final String JDBC_URL="jdbc.url";
	private static final String JDBC_USERNAME="jdbc.username";
	private static final String JDBC_PASSWORD="jdbc.password";
	
	private static final String FILESERVER_IP="fileserver.ip";
	
	private static final String FFMPEGPATH="ffmpegPath";
	 
	private static final String INTERVAL_FAILSRETRY="interval.failsRetry";
	private static final String INTERVAL_EACHTIME="interval.eachTime";
	
	private static final String CONVERTER_QSCALE_HIGH="converter.qscale.high";
	private static final String CONVERTER_QSCALE_MIDDLE="converter.qscale.middle";
	private static final String CONVERTER_QSCALE_LOW="converter.qscale.low";
	private static final String CONVERTER_RESOLUTION="converter.resolution";
	
	private static Pattern resolutionPattern = Pattern.compile("^(\\d*)\\*(\\d*)$");
	
	//数据库驱动
	private String jdbcDriverName=null;
	//数据库连接地址
	private String jdbcUrl=null;
	//数据库用户名
	private String jdbcUsername=null;
	//数据库密码
	private String jdbcPassword=null;
	
	//文件服务器的IP地址,用于获取本机可能保存的记录
	private String fileServerIP=null;
	
	//ffmpeg工具的路径
	private String ffmpegPath=null;
	
	//运行过程中连接数据库失败之后等待多长时间之后再执行转换任务(单位分钟)
	private Integer intervalFailsRetry=null;
	//本次转换任务完成之后与下次运行转换任务之后的等待时间(单位分钟)
	private Integer intervalEachTime=null;
	
	private Integer converterQscaleHigh=10;
	private Integer converterQscaleMiddle=10;
	private Integer converterQscaleLow=10;
	private String converterResolution="640*360";
	
	/**
	 * 程序运行的过程中会生成.lock锁文件,当新程序实例运行时会试着获取.lock文件的锁,
	 * 如果获取不到锁，则说明有另外一个程序实例在运行，此时本实例就直接退出.
	 */
	public void lockFile(){
		try {
			File f=new File(".lock");
			f.deleteOnExit();
			f.createNewFile();
			RandomAccessFile  raf = new RandomAccessFile(f, "rw");
			FileChannel  channel = raf.getChannel();
			FileLock lock = channel.tryLock();

			 if (lock == null) {
			     // 如果没有得到锁，则程序退出.
			     // 没有必要手动释放锁和关闭流，当程序退出时，他们会被关闭的.
			     throw new Exception("An instance of the application is running.");
			 }
		} catch (Exception e) {
			logger.error("检测到可能有另外一个程序实例正在运行或者上一次运行没有正常关闭,请检查同目录下是否存在.lock文件,如果存在则直接删除之后再运行本程序,如果没有则再次运行本程序即可...");
			System.exit(0);
		}
	}
	
	/**
	 * 进行系统中的相关参数进行初始化并进行验证.
	 */
	public void init(){
		logger.info("********************************************");
		logger.info("\"执法记录仪\"管理系统之定时视频格式转换客户端开始启动...");
		logger.info("********************************************");
		logger.info("系统正在进行检查,请稍后...");

		lockFile();
		
		logger.info("系统正在初始化配置参数...");
		
		//读取配置文件
		Properties props=new Properties();
		try{
			File file=new File("conf/application.properties");
			InputStream in=(InputStream) new FileInputStream(file);
			props.load(in);
			logger.info("系统成功找到[conf/application.properties]配置文件...");
		}catch(IOException e){
			logger.error("系统未找到[conf/application.properties]配置文件,程序自动退出...");
			System.exit(0);
		}
		
		jdbcDriverName=props.getProperty(JDBC_DRIVERNAME, "").trim();
		if("".equals(jdbcDriverName)){
			logger.error("读取配置文件内容出错,程序自动退出,请您确认是否设置了jdbc.driverName的值...");
			System.exit(0);
		}
		
		jdbcUrl=props.getProperty(JDBC_URL,"").trim();
		if("".equals(jdbcUrl)){
			logger.error("读取配置文件内容出错,程序自动退出,请您确认是否设置了jdbc.url的值...");
			System.exit(0);
		}
		
		jdbcUsername=props.getProperty(JDBC_USERNAME,"").trim();
		if("".equals(jdbcUsername)){
			logger.error("读取配置文件内容出错,程序自动退出,请您确认是否设置了jdbc.username的值...");
			System.exit(0);
		}
		
		jdbcPassword=props.getProperty(JDBC_PASSWORD,"").trim();
		if("".equals(jdbcPassword)){
			logger.error("读取配置文件内容出错,程序自动退出,请您确认是否设置了jdbc.password的值...");
			System.exit(0);
		}
		
		ffmpegPath=props.getProperty(FFMPEGPATH,"").trim();
		if("".equals(ffmpegPath)){
			logger.error("读取配置文件内容出错,程序自动退出,请您确认是否设置了ffmpegPath的值...");
			System.exit(0);
		}

		if(!(new File(ffmpegPath).exists())){
			logger.error("读取配置文件内容出错,程序自动退出,请您确认ffmpegPath的值所表示的文件是否存在...");
			System.exit(0);
		}
		
		fileServerIP=props.getProperty(FILESERVER_IP,"").trim();
		if("".equals(fileServerIP)){
			logger.error("读取配置文件内容出错,程序自动退出,请您确认是否设置了fileserver.ip的值...");
			System.exit(0);
		}
		
		String tmpConverterQscaleHigh=props.getProperty(CONVERTER_QSCALE_HIGH,"10").trim();
		if("".equals(tmpConverterQscaleHigh)){
			logger.error("读取配置文件内容出错,程序自动退出,请您确认是否设置了converter.qscale.high的值...");
			System.exit(0);
		}
		
		if(!isDigits(tmpConverterQscaleHigh)){
			logger.error("读取配置文件内容出错,程序自动退出,请您确认converter.qscale.high的值是否为数字...");
			System.exit(0);
		}
		
		converterQscaleHigh=Integer.valueOf(tmpConverterQscaleHigh);
		
		String tmpConverterQscaleMiddle=props.getProperty(CONVERTER_QSCALE_MIDDLE,"10").trim();
		if("".equals(tmpConverterQscaleMiddle)){
			logger.error("读取配置文件内容出错,程序自动退出,请您确认是否设置了converter.qscale.middle的值...");
			System.exit(0);
		}
		
		if(!isDigits(tmpConverterQscaleMiddle)){
			logger.error("读取配置文件内容出错,程序自动退出,请您确认converter.qscale.middle的值是否为数字...");
			System.exit(0);
		}
		
		converterQscaleMiddle=Integer.valueOf(tmpConverterQscaleMiddle);
		
		String tmpConverterQscaleLow=props.getProperty(CONVERTER_QSCALE_LOW,"10").trim();
		if("".equals(tmpConverterQscaleLow)){
			logger.error("读取配置文件内容出错,程序自动退出,请您确认是否设置了converter.qscale.low的值...");
			System.exit(0);
		}
		
		if(!isDigits(tmpConverterQscaleLow)){
			logger.error("读取配置文件内容出错,程序自动退出,请您确认converter.qscale.low的值是否为数字...");
			System.exit(0);
		}
		
		converterQscaleLow=Integer.valueOf(tmpConverterQscaleLow);
		
		converterResolution=props.getProperty(CONVERTER_RESOLUTION,"640*360").trim();
		if("".equals(converterResolution)){
			logger.error("读取配置文件内容出错,程序自动退出,请您确认是否设置了converter.resolution的值...");
			System.exit(0);
		}
		
		Matcher mat = resolutionPattern.matcher(converterResolution); 
		boolean find = mat.find();   
		
		if(!find){
			logger.error("读取配置文件内容出错,程序自动退出,请您确认是否设置了converter.resolution的值必须以*分隔，例如640*360...");
			System.exit(0);
		}
		
		String tmpIntervalFailsRetry=props.getProperty(INTERVAL_FAILSRETRY,"5").trim();
		if("".equals(tmpIntervalFailsRetry)){
			logger.error("读取配置文件内容出错,程序自动退出,请您确认是否设置了interval.failsRetry的值...");
			System.exit(0);
		}
		
		if(!isDigits(tmpIntervalFailsRetry)){
			logger.error("读取配置文件内容出错,程序自动退出,请您确认interval.failsRetry的值是否为数字...");
			System.exit(0);
		}
		
		intervalFailsRetry=Integer.valueOf(tmpIntervalFailsRetry);
		
		String tmpIntervalEachTime=props.getProperty(INTERVAL_EACHTIME,"5").trim();
		if("".equals(tmpIntervalEachTime)){
			logger.error("读取配置文件内容出错,程序自动退出,请您确认是否设置了interval.eachTime的值...");
			System.exit(0);
		}
		
		if(!isDigits(tmpIntervalEachTime)){
			logger.error("读取配置文件内容出错,程序自动退出,请您确认interval.eachTime的值是否为数字...");
			System.exit(0);
		}
		
		intervalEachTime=Integer.valueOf(tmpIntervalEachTime);
		
		logger.info("系统读取[conf/application.properties]配置文件信息成功...");
		
	}
	
	/**
	 * 加载数据库驱动
	 */
	public void loadDriver(){
		try {
			Class.forName(jdbcDriverName);
		} catch (ClassNotFoundException e) {
			logger.error("加载数据库驱动失败,程序自动退出,请使用目前支持的Oracle数据库...");
			System.exit(0);
		}
	}
	
	/**
	 * 对配置的数据库相关信息进行检测连接
	 */
	public void testConnection(){
		Connection conn =null;
		try {
			conn=DriverManager.getConnection(jdbcUrl,jdbcUsername,jdbcPassword);
			conn.close();
		} catch (SQLException e) {
			logger.error("连接数据库失败,程序自动退出,请确认您设置的数据库用户名和密码是否正确...");
			System.exit(0);
		}
	}
	
	/**
	 * 注册系统关闭hook
	 */
	public void registerShutdownHook(){
		if(shutdownHook==null){
			shutdownHook=new ShutdownHook();
		}
		Runtime.getRuntime().addShutdownHook(shutdownHook);
	}
	
	/**
	 * 开启视频转换线程
	 */
	public void start(){
		executor=Executors.newSingleThreadScheduledExecutor();
		t=new ConverterThread();
		executor.submit(t);
	}
	
    public static boolean isDigits(String str) {
        if (str == null || str.length() == 0) {
            return false;
        }
        for (int i = 0; i < str.length(); i++) {
            if (!Character.isDigit(str.charAt(i))) {
                return false;
            }
        }
        
        return true;
    }
	
    /**
     * JVM关闭hook线程，当在命令行窗口中按下ctrl+C中断程序执行时，会执行些方法，但是
     * 不是对直接关闭窗口进行Hook
     *
     */
	protected class ShutdownHook extends Thread{
		public void run(){
			try{
				logger.info("系统正在退出,请稍后...");
				if(null!=t)
					t.setStop(true); //设置线程退出标志
				if(null!=executor)
					executor.shutdown();	
				logger.info("系统已退出...");
			}catch(Throwable ex){
				ex.printStackTrace();
			}finally{
				
			}
		}
	}

	/**
	 * 视频转换线程
	 */
	protected class ConverterThread  implements Runnable{
		
		//是否结束标志
		private boolean isStop=false;
		
		public void setStop(boolean isStop) {
			this.isStop = isStop;
		}
		
		@Override
		public void run() {
			boolean flag=true;
			while(!isStop){
				flag=true;
				logger.info("执行转换任务...");
			 	
				Connection conn=null;
				ResultSet rs=null;
				try {
					conn=DriverManager.getConnection(jdbcUrl,jdbcUsername,jdbcPassword);
					
					//检测是否存在上次更新数据库记录失败记录的备份文件
					File f=new File(".bak");
					String s =null;
					if(f.exists()){
						try {
							BufferedReader input = new BufferedReader(new FileReader(f));
							s= input.readLine();
							input.close();
						} catch (Exception e1) {
						}
						if(null!=s && s.trim().length()>0){
							logger.info("系统提示:发现上次执行视频转换之后更新文件记录失败的记录["+s+"],因此先进行更新操作!");
							try {
								rs=conn.createStatement().executeQuery(
						 				"select * from file_upload_info where file_context_path like '%"+fileServerIP+"%' and file_status='C' and file_id='"+s+"'");
						 		if(rs.next()){
						 			String fileStorageRoot=rs.getString("file_storage_root");
				 					
					 				String fileSavePath=rs.getString("file_save_path");
					 				
						 			String filePlayPath=fileSavePath.substring(0,fileSavePath.lastIndexOf("."))+".flv";
						 			String fileDestPath=fileStorageRoot+filePlayPath;
						 			
						 			if(new File(fileDestPath).exists()){
						 				conn.createStatement().execute("update file_upload_info set file_play_path='"+filePlayPath+"' , file_status='P' where file_id='"+rs.getString("file_id")+"'");
						 				logger.info("系统提示:更新文件记录["+rs.getString("file_id")+"]为剪辑完成状态的操作执行成功!");
						 			}else{
						 				logger.info("系统提示:未发现转换之后的文件,因此不进行任何操作!");
						 			}
						 		}else{
						 			logger.info("系统提示:未发现文件为["+s+"]的记录,因此不进行任何操作!");
						 		}
						 	} catch (SQLException e) {
				 				logger.error("系统提示:更新文件记录["+rs.getString("file_id")+"]为剪辑完成状态的操作执行失败!");
				 				throw e;
						 	}finally{
				 				new File(".bak").delete();
				 			}
							try { rs.close(); } catch (Exception e) {}
						}
					}
					
					//进行本次的转换操作
			 		rs=conn.createStatement().executeQuery(
			 				"select * from(select * from file_upload_info where file_context_path like '%"+fileServerIP+"%' and file_status='C' " +
			 							" order by file_upload_time) where rownum<=1 ");
			 		
			 		String fileStorageRoot="";
			 		String fileSavePath="";
			 		String fileId="";
			 		boolean hasRecord=false;
			 		if(rs.next()){
	 					fileStorageRoot=rs.getString("file_storage_root");
	 					
		 				fileSavePath=rs.getString("file_save_path");
		 				
		 				fileId=rs.getString("file_id");
		 				
		 				hasRecord=true;
			 		}
			 		try {if(null!=rs){ rs.close(); } } catch (Exception e) {}
			 		try {if(null!=conn){ conn.close(); } } catch (Exception e) {}

			 		if(hasRecord){
				 		String fileSourcePath=fileStorageRoot+fileSavePath;
				 			 
						String filePlayPath=fileSavePath.substring(0,fileSavePath.lastIndexOf("."))+".flv";
						String fileDestPath=fileStorageRoot+filePlayPath;
				 				
						if(checkContentType(fileSavePath)!=0){
			 				logger.info("系统提示:将要进行视频转换["+fileSourcePath+"]的文件格式目录本工具暂不支持,因此放弃此视频的转换!");
			 				try {
			 					conn=DriverManager.getConnection(jdbcUrl,jdbcUsername,jdbcPassword);
								conn.createStatement().execute("update file_upload_info set file_status='A' where file_id='"+fileId+"'");
								logger.info("系统提示:更新文件记录["+fileId+"]为有效状态的操作执行成功!");
					 		} catch (SQLException e) {
					 			logger.error("系统提示:更新文件记录["+fileId+"]为有效状态的操作执行失败!");
					 			throw e;
					 		}
						}else{
				 		
					 		logger.info("系统提示:正在进行视频转换["+fileSourcePath+"]->["+fileDestPath+"],文件记录["+fileId+"]!");
					 		boolean isOk=makeFlvByMP4(ffmpegPath,fileSourcePath,fileDestPath);
					 		if(isOk){
					 			logger.info("系统提示:视频["+fileSourcePath+"]->["+fileDestPath+"]转换结束,文件记录["+fileId+"]!");
					 			try {
					 				conn=DriverManager.getConnection(jdbcUrl,jdbcUsername,jdbcPassword);
									conn.createStatement().execute("update file_upload_info set file_play_path='"+filePlayPath+"' , file_status='P' where file_id='"+fileId+"'");
									logger.info("系统提示:更新文件记录["+fileId+"]为剪辑完成状态的操作执行成功!");
						 		} catch (SQLException e) {
						 			logger.error("系统提示:更新文件记录["+fileId+"]为剪辑完成状态的操作执行失败!");
						 		
						 			try {
						 				File f1=new File(".bak");
						 				f1.createNewFile();
						 				BufferedWriter output = new BufferedWriter(new FileWriter(f1));
						 				output.write(fileId);
						 				output.close();
						 			} catch (Exception e1) {
						 			}
						 			throw e;
						 		}
					 		}else{
					 			logger.info("系统提示:视频["+fileSavePath+"]->["+filePlayPath+"]转换失败,文件记录["+fileId+"]!");
					 		}
				 		}
			 		}else{
			 			logger.info("未发现需要进行视频转换的文件记录!");
			 		}
				} catch (SQLException e) {
					e.printStackTrace();
					logger.error("查询数据库信息失败,请检查数据库服务器的状态,系统将在"+intervalFailsRetry+"分钟之后再试...");
					int tmp=intervalFailsRetry*60;
					while(!isStop && tmp>0){
						try {
							Thread.sleep(1000);
						} catch (InterruptedException e1) {
						} finally{
							tmp--;
							if(tmp % 60 ==0)
								logger.error("查询数据库信息失败,请检查数据库服务器的状态,系统将在"+(tmp/60)+"分钟之后再试...");
						}
					}
					flag=false;
				}finally{
					if(null!=conn)
						try{ conn.close(); }catch(Exception e){}
					
					if(null!=rs)
						try{ rs.close(); }catch(Exception e){}
				}
				if(flag){
					int tmp=intervalEachTime*60;
					logger.info("本次执行转换任务完成,下次执行转换任务将在"+(tmp/60)+"分钟之后执行...");
					while(!isStop && tmp>0){
						try {
							Thread.sleep(1000);
						} catch (InterruptedException e1) {
						} finally{
							tmp--;
							if(tmp % 60 ==0)
								logger.info("本次执行转换任务完成,下次执行转换任务将在"+(tmp/60)+"分钟之后执行...");
							
						}
					}
				}
			}
		}

	}
	
	/**
	 * 主程序入口
	 */
	public static void main(String[] args) {
		Client client=new Client();
		client.init();
		client.loadDriver();
		client.testConnection();
		client.start();
		client.registerShutdownHook();
		
//		client.getVideoInfo("D:\\Program Files (x86)\\ffmpeg\\bin\\ffmpeg","D:\\sdsd\\20130423_154755_327.mp4");
	}
	
	/**
	 * 获取视频的相关信息
	 * @param ffmpegPath
	 * @param fileSavePath
	 * @return
	 */
	public Map<String,String> getVideoInfo(String ffmpegPath,String fileSavePath){
		Map<String,String> results=new HashMap<String,String>();
		
		List<String> commend = new java.util.ArrayList<String>();
		commend.add(ffmpegPath);
		commend.add("-y");
		commend.add("-i");
		commend.add(fileSavePath);
		
		BufferedReader br=null;
		
		try {
			ProcessBuilder builder = new ProcessBuilder();
			builder.command(commend);
			Process proc=builder.start();
			br=new BufferedReader(new InputStreamReader(proc.getErrorStream()));
			StringBuffer sb=new StringBuffer();
			String b=null;
            while((b=br.readLine())!=null){
            	sb.append(b).append("\n");
            }
            proc.waitFor();
            
            //分析整个文件的时长，开始时间，码率
            String result=sb.toString();
            String regexDuration = "Duration: (.*?), start: (.*?), bitrate: (\\d*) kb\\/s";
            Pattern pattern = Pattern.compile(regexDuration);
            Matcher m = pattern.matcher(result);
            if (m.find()) {
            	results.put("duration",m.group(1));
            	results.put("start",m.group(2));
            	results.put("bitrate",m.group(3)+" kb/s");
            }else{
            	results.put("duration","");
            	results.put("start","");
            	results.put("bitrate","");
            }
            
            //分析分辨率
            String resolutionDuration = "Video:(.*?),(.*?),(.*?)(\\d*)x(\\d*)";
            pattern = Pattern.compile(resolutionDuration);
            m = pattern.matcher(result);
            if (m.find()) {
            	results.put("resolution",m.group(4)+"x"+m.group(5));
            }else{
            	results.put("resolution","");
            }
		} catch (Exception e) {
			e.printStackTrace();
		}finally{   
            if(br!=null){   
                try {   
                    br.close();   
                } catch (Exception e) {   
                    e.printStackTrace();   
                }   
            }   
        }   
		return results;
	}
	
	/**
	 * 进行视频转换的方法
	 * @param ffmpegPath
	 * @param fileSavePath
	 * @param filePlayPath
	 * @return
	 */
	public boolean makeFlvByMP4(String ffmpegPath,String fileSavePath,String filePlayPath){
		int converterQscale=converterQscaleMiddle;
		
		//获取视频的相关信息
		Map<String,String> infos=getVideoInfo(ffmpegPath, fileSavePath);
		//根据分辨率获得ascale
		if(infos.containsKey("resolution") && infos.get("resolution").length()>0){
			Matcher mat = resolutionPattern.matcher(infos.get("resolution"));  
			if(mat.find()){
				int tmpResolution=Integer.valueOf(mat.group(1));
				
				if(tmpResolution>=1440){
					converterQscale=converterQscaleHigh;
				}else if(tmpResolution<=848){
					converterQscale=converterQscaleLow;
				}
			}
		}
		
		List<String> commend = new java.util.ArrayList<String>();
		commend.add(ffmpegPath);
		commend.add("-y");
		commend.add("-i");
		commend.add(fileSavePath);
		commend.add("-qscale");
		commend.add(""+converterQscale);
		commend.add("-ar");
		commend.add("44100");
		commend.add("-s");
		commend.add(converterResolution);
		commend.add(filePlayPath);
		
		BufferedReader br=null;
		
		try {
			ProcessBuilder builder = new ProcessBuilder();
			builder.command(commend);
			Process proc=builder.start();
			br=new BufferedReader(new InputStreamReader(proc.getErrorStream()));   
            String b;
            while((b=br.readLine())!=null){
            	//向终端打印转换信息，以使用户知道当前程序正在运行。
            	System.out.println(b);
            }
            proc.waitFor();
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}finally{   
            if(br!=null){   
                try {   
                    br.close();   
                } catch (Exception e) {   
                    e.printStackTrace();   
                }   
            }   
        }   
		
		return true;
	}
	
	/**
	 * 对将要进行视频格式转换的文件进行支持情况判断
	 */
    private int checkContentType(String path) {
        String type = path.substring(path.lastIndexOf(".") + 1).toLowerCase();
//ffmpeg能解析的格式：（asx，asf，mpg，wmv，3gp，mp4，mov，avi，flv等）
        if (type.equals("avi")) {
            return 0;
        } else if (type.equals("mpg")) {
            return 0;
        } else if (type.equals("wmv")) {
            return 0;
        } else if (type.equals("3gp")) {
            return 0;
        } else if (type.equals("mov")) {
            return 0;
        } else if (type.equals("mp4")) {
            return 0;
        } else if (type.equals("asf")) {
            return 0;
        } else if (type.equals("asx")) {
            return 0;
        } else if (type.equals("flv")) {
            return 0;
        }
        //对ffmpeg无法解析的文件格式(wmv9，rm，rmvb等), 可以先用别的工具（mencoder）转换为avi(ffmpeg能解析的)格式.
        else if (type.equals("wmv9")) {
            return 1;
        } else if (type.equals("rm")) {
            return 1;
        } else if (type.equals("rmvb")) {
            return 1;
        }       
        return 9;
    }
}
