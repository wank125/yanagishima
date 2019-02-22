package yanagishima.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.geso.tinyorm.TinyORM;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import yanagishima.config.YanagishimaConfig;
import yanagishima.row.Query;
import yanagishima.util.*;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;

@Singleton
public class HiveQueryStatusServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	private YanagishimaConfig yanagishimaConfig;

	@Inject
	private TinyORM db;

	@Inject
	public HiveQueryStatusServlet(YanagishimaConfig yanagishimaConfig) {
		this.yanagishimaConfig = yanagishimaConfig;
	}

	@Override
	protected void doPost(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {

		String datasource = HttpRequestUtil.getParam(request, "datasource");
		if(yanagishimaConfig.isCheckDatasource()) {
			if(!AccessControlUtil.validateDatasource(request, datasource)) {
				try {
					response.sendError(SC_FORBIDDEN);
					return;
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		}
		String queryid = HttpRequestUtil.getParam(request, "queryid");
		String resourceManagerUrl = yanagishimaConfig.getResourceManagerUrl(datasource);
		String userName = null;
		if(yanagishimaConfig.isUseAuditHttpHeaderName()) {
			userName = request.getHeader(yanagishimaConfig.getAuditHttpHeaderName());
		} else {
			Optional<String> hiveUser = Optional.ofNullable(request.getParameter("user"));
			if (hiveUser.isPresent()) {
				userName = hiveUser.get();
			}
		}

		String engine = HttpRequestUtil.getParam(request, "engine");
		Optional<Query> queryOptional = db.single(Query.class).where("query_id=? and datasource=? and engine=?", queryid, datasource, engine).execute();
		if(engine.equals("hive")) {
			Optional<Map> applicationOptional = YarnUtil.getApplication(resourceManagerUrl, queryid, userName, yanagishimaConfig.getResourceManagerBegin(datasource));
			if(applicationOptional.isPresent()) {
				try {
					response.setContentType("application/json");
					PrintWriter writer = response.getWriter();
					ObjectMapper mapper = new ObjectMapper();
					String json = mapper.writeValueAsString(applicationOptional.get());
					writer.println(json);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			} else {
				if(!queryOptional.isPresent()) {
					HashMap<String, Object> retVal = new HashMap<String, Object>();
					retVal.put("state", "RUNNING");
					retVal.put("progress", 0);
					retVal.put("elapsedTime", 0);
					JsonUtil.writeJSON(response, retVal);
				}
			}
		} else if(engine.equals("spark")) {
			HashMap<String, Object> retVal = new HashMap<String, Object>();
			if (queryOptional.isPresent()) {
				if (queryOptional.get().getStatus().equals(Status.SUCCEED.name())) {
					retVal.put("state", "FINISHED");
				} else if (queryOptional.get().getStatus().equals(Status.FAILED.name())) {
					retVal.put("state", "FAILED");
				} else {
					throw new IllegalArgumentException(String.format("unknown status=%s", queryOptional.get().getStatus()));
				}
			} else {
                String sparkWebUrl = yanagishimaConfig.getSparkWebUrl(datasource);
                OkHttpClient client = new OkHttpClient();
                Request okhttpRequest = new Request.Builder().url(sparkWebUrl).build();
                try (Response okhttpResponse = client.newCall(okhttpRequest).execute()) {
                    HttpUrl url = okhttpResponse.request().url();
                    String sparkJdbcApplicationId = url.pathSegments().get(url.pathSize() - 2);
                    if (!sparkJdbcApplicationId.startsWith("application_")) {
                        throw new IllegalArgumentException(sparkJdbcApplicationId + " is illegal");
                    }
                    Optional<Map> runningJob = SparkUtil.getRunningJob(resourceManagerUrl, sparkJdbcApplicationId, queryid);
                    if(runningJob.isPresent()) {
                        response.setContentType("application/json");
                        PrintWriter writer = response.getWriter();
                        ObjectMapper mapper = new ObjectMapper();
                        Map map = runningJob.get();
                        String status = (String)map.get("status");
                        map.remove("status");
                        map.put("state", status);
                        int numTasks = (int)map.get("numTasks");
                        int numCompletedTasks = (int)map.get("numCompletedTasks");
                        double progress = ((double)numCompletedTasks/numTasks)*100;
                        map.put("progress", progress);
                        String json = mapper.writeValueAsString(map);
                        writer.println(json);
                        return;
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
				retVal.put("state", "RUNNING");
				retVal.put("progress", 0);
				retVal.put("elapsedTime", 0);
			}
			JsonUtil.writeJSON(response, retVal);
		} else {
			throw new IllegalArgumentException(engine + " is illegal");
		}


	}

}
