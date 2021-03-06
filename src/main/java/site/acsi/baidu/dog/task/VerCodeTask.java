package site.acsi.baidu.dog.task;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import retrofit2.Call;
import site.acsi.baidu.dog.config.GlobalConfig;
import site.acsi.baidu.dog.invoke.PetOperationInvoke;
import site.acsi.baidu.dog.invoke.vo.BaseRequest;
import site.acsi.baidu.dog.invoke.vo.VerificationCodeResponse;
import site.acsi.baidu.dog.pojo.Acount;
import site.acsi.baidu.dog.pojo.VerificationCode;
import site.acsi.baidu.dog.pojo.VerificationCodeData;
import site.acsi.baidu.dog.service.IVerCodeParseService;
import site.acsi.baidu.dog.util.ImageUtils;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * @author Acsi
 * @date 2018/2/10
 */

@Slf4j
@Component
@EnableScheduling
public class VerCodeTask {

    @Resource
    private PetOperationInvoke petOperationInvoke;

    @Resource
    private Map<String, IVerCodeParseService> verCodeServices;

    @Resource
    private GlobalConfig config;

    @Resource
    private ImageUtils imageUtils;

    private Map<Acount, Queue<VerificationCode>> queueMap = Maps.newConcurrentMap();


    private static final int APP_ID = 1;
    private static final int ONE_SECOND = 1000;
    private static final int SAFE_QUEUE_SIZE = 1;
    private static final int VALID_TIME = 120000;

    @PostConstruct
    @SneakyThrows
    public void init() {
        queueMap.clear();
        config.getConfig().getAcounts().forEach((acount -> queueMap.put(acount, Lists.newLinkedList())));
    }

    public VerificationCode getVerCodeInfo(Acount acount) {
        if (queueMap.get(acount).isEmpty()) {
            storeVerCode(acount);
        }
        return queueMap.get(acount).poll();
    }

    @Scheduled(fixedDelay = 2000)
    public void doTask() {
        if (!config.getConfig().getIsExecutable()) {
            return;
        }
        List<Acount> acounts = config.getConfig().getAcounts();
        clearInvalidVerCode(acounts);
        Acount acount = acounts.get((int) (System.currentTimeMillis() % acounts.size()));
        genVerCodeByAcount(acount);
    }

    private void clearInvalidVerCode(List<Acount> acounts) {
        acounts.forEach(
                acount -> {
                    Queue<VerificationCode> queue = queueMap.get(acount);
                    while (!queue.isEmpty()) {
                        if (System.currentTimeMillis() - queue.peek().getCreateTime() > VALID_TIME) {
                            queue.poll();
                        } else {
                            break;
                        }
                    }
                }
        );
    }

    private void genVerCodeByAcount(Acount acount) {
        Queue<VerificationCode> queue = queueMap.get(acount);
        while (!queue.isEmpty()) {
            if (System.currentTimeMillis() - queue.peek().getCreateTime() > VALID_TIME) {
                queue.poll();
            } else {
                break;
            }
        }
        if (queue.size() < SAFE_QUEUE_SIZE) {
            storeVerCode(acount);
        }
    }

    @SneakyThrows
    private void storeVerCode(Acount acount) {
        VerificationCodeData data = null;
        try {
            data = genVerificationCode(acount);
        } catch (Throwable e) {
            log.error("请求验证码失败", e);
            Thread.sleep(60000);
        }
        Preconditions.checkNotNull(data);
        try {
            String code = verCodeServices.get(config.getConfig().getVerCodeStrategy()).predict(data.getImg());
            queueMap.get(acount).offer(
                    new VerificationCode(
                            data.getSeed(),
                            code,
                            System.currentTimeMillis()));
            if (config.getConfig().getLogSwitch()) {
                log.info("储备验证码成功，user:{} code:{}", acount.getDes(), code);
            }
            if (config.getConfig().getExportSwitch()) {
                imageUtils.convertBase64DataToImage(data.getImg(), config.getConfig().getExportVerCodeImgPath() + "/" + code + System.currentTimeMillis()%1000 + ".jpg");
            }
        } catch (IOException e) {
            if (config.getConfig().getLogSwitch()) {
                log.error("识别验证码失败", e);
            }
        }
    }

    @SneakyThrows
    private VerificationCodeData genVerificationCode(Acount acount) {
        BaseRequest request = new BaseRequest();
        request.setAppId(APP_ID);
        request.setRequestId(System.currentTimeMillis());
        request.setTpl("");
        Call<VerificationCodeResponse> call = petOperationInvoke.genVerificationCode(request, acount.getCookie());
        VerificationCodeResponse response = call.execute().body();
        if (response == null) {
            log.info("=== 返回验证码数据为空 user:{}", acount.getDes());
            Thread.sleep(60 * ONE_SECOND);
        }
        Preconditions.checkNotNull(response);
        if (response.getData() == null) {
            log.info("== 验证码data为空，可能需要更新cookie user:{}, response:{}", acount.getDes(), response);
            Thread.sleep(60 * ONE_SECOND);
            return genVerificationCode(acount);
        }
        Preconditions.checkNotNull(response.getData());
        return response.getData();
    }

}
