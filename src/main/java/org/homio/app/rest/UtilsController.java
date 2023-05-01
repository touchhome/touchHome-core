package org.homio.app.rest;

import static javax.ws.rs.core.MediaType.APPLICATION_OCTET_STREAM;
import static org.homio.app.rest.widget.EvaluateDatesAndValues.convertValuesToFloat;
import static org.homio.bundle.api.util.CommonUtils.OBJECT_MAPPER;
import static org.homio.bundle.api.util.Constants.ADMIN_ROLE;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.security.RolesAllowed;
import javax.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import lombok.val;
import net.rossillo.spring.web.mvc.CacheControl;
import net.rossillo.spring.web.mvc.CachePolicy;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.homio.app.js.assistant.impl.CodeParser;
import org.homio.app.js.assistant.impl.ParserContext;
import org.homio.app.js.assistant.model.Completion;
import org.homio.app.js.assistant.model.CompletionRequest;
import org.homio.app.manager.ScriptService;
import org.homio.app.manager.common.EntityContextImpl;
import org.homio.app.model.entity.ScriptEntity;
import org.homio.app.model.entity.widget.impl.DataSourceUtil;
import org.homio.app.model.entity.widget.impl.DataSourceUtil.DataSourceContext;
import org.homio.app.model.entity.widget.impl.js.WidgetFrameEntity;
import org.homio.app.model.rest.DynamicUpdateRequest;
import org.homio.app.rest.widget.ChartDataset;
import org.homio.app.rest.widget.EvaluateDatesAndValues;
import org.homio.app.rest.widget.WidgetChartsController;
import org.homio.app.rest.widget.WidgetChartsController.TimeSeriesChartData;
import org.homio.bundle.api.EntityContextUI;
import org.homio.bundle.api.entity.widget.AggregationType;
import org.homio.bundle.api.entity.widget.PeriodRequest;
import org.homio.bundle.api.entity.widget.ability.HasGetStatusValue;
import org.homio.bundle.api.entity.widget.ability.HasGetStatusValue.GetStatusValueRequest;
import org.homio.bundle.api.entity.widget.ability.HasTimeValueSeries;
import org.homio.bundle.api.exception.NotFoundException;
import org.homio.bundle.api.exception.ServerException;
import org.homio.bundle.api.model.ActionResponseModel;
import org.homio.bundle.api.storage.SourceHistory;
import org.homio.bundle.api.storage.SourceHistoryItem;
import org.homio.bundle.api.util.CommonUtils;
import org.homio.bundle.api.util.Curl;
import org.homio.bundle.api.util.Lang;
import org.json.JSONObject;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Log4j2
@RestController
@RequestMapping("/rest")
@RequiredArgsConstructor
public class UtilsController {

    private final EntityContextImpl entityContext;
    private final ScriptService scriptService;
    private final CodeParser codeParser;

    @PutMapping("/multiDynamicUpdates")
    public void multiDynamicUpdates(@Valid @RequestBody List<DynamicRequestItem> request) {
        for (DynamicRequestItem requestItem : request) {
            entityContext.ui().registerForUpdates(new DynamicUpdateRequest(requestItem.did, requestItem.eid));
        }
    }

    @PutMapping("/dynamicUpdates")
    public void registerForUpdates(@Valid @RequestBody DynamicUpdateRequest request) {
        entityContext.ui().registerForUpdates(request);
    }

    @DeleteMapping("/dynamicUpdates")
    public void unregisterForUpdates(@Valid @RequestBody DynamicUpdateRequest request) {
        entityContext.ui().unRegisterForUpdates(request);
    }

    @GetMapping("/app/config")
    public DeviceConfig getAppConfiguration() {
        return new DeviceConfig();
    }

    @PostMapping("/source/history/info")
    public SourceHistory getSourceHistory(@RequestBody SourceHistoryRequest request) {
        DataSourceContext context = DataSourceUtil.getSource(entityContext, request.dataSource);
        val historyRequest = new GetStatusValueRequest(entityContext, request.dynamicParameters);
        return ((HasGetStatusValue) context.getSource()).getSourceHistory(historyRequest);
    }

    @PostMapping("/source/chart")
    public WidgetChartsController.TimeSeriesChartData<ChartDataset> getSourceChart(@RequestBody SourceHistoryChartRequest request) {
        DataSourceContext context = DataSourceUtil.getSource(entityContext, request.dataSource);
        WidgetChartsController.TimeSeriesChartData<ChartDataset> chartData = new TimeSeriesChartData<>();
        if (context.getSource() instanceof HasTimeValueSeries) {
            PeriodRequest periodRequest = new PeriodRequest(entityContext, null, null).setParameters(request.getDynamicParameters());
            val timeSeries = ((HasTimeValueSeries) context.getSource()).getMultipleTimeValueSeries(periodRequest);
            List<Object[]> rawValues = timeSeries.values().iterator().next();

            if (!timeSeries.isEmpty()) {
                Pair<Long, Long> minMax = this.findMinAndMax(rawValues);
                long min = minMax.getLeft(), max = minMax.getRight();
                long delta = (max - min) / request.splitCount;
                List<Date> dates = IntStream.range(0, request.splitCount)
                                            .mapToObj(value -> new Date(min + delta * value))
                                            .collect(Collectors.toList());
                List<List<Float>> values = convertValuesToFloat(dates, rawValues);

                chartData.setTimestamp(dates.stream().map(Date::getTime).collect(Collectors.toList()));

                ChartDataset dataset = new ChartDataset(null, null);
                dataset.setData(EvaluateDatesAndValues.aggregate(values, AggregationType.AverageNoZero));
                chartData.getDatasets().add(dataset);
            }
        }
        return chartData;
    }

    private Pair<Long, Long> findMinAndMax(List<Object[]> rawValues) {
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (Object[] chartItem : rawValues) {
            min = Math.min(min, (long) chartItem[0]);
            max = Math.max(max, (long) chartItem[0]);
        }
        return Pair.of(min, max);
    }

    @PostMapping("/source/history/items")
    public List<SourceHistoryItem> getSourceHistoryItems(@RequestBody SourceHistoryRequest request) {
        DataSourceContext context = DataSourceUtil.getSource(entityContext, request.dataSource);
        val historyRequest = new GetStatusValueRequest(entityContext, request.dynamicParameters);
        return ((HasGetStatusValue) context.getSource()).getSourceHistoryItems(historyRequest,
            request.getFrom(), request.getCount());
    }

    @GetMapping("/frame/{entityID}")
    public String getFrame(@PathVariable("entityID") String entityID) {
        WidgetFrameEntity widgetFrameEntity = entityContext.getEntityRequire(entityID);
        return widgetFrameEntity.getFrame();
    }

    @PostMapping("/github/readme")
    public GitHubReadme getUrlContent(@RequestBody String url) {
        try {
            if (url.endsWith("/wiki")) {
                url = url.substring(0, url.length() - 5);
            }
            return new GitHubReadme(url, Curl.get(url + "/raw/master/README.md", String.class));
        } catch (Exception ex) {
            throw new ServerException("No readme found");
        }
    }

    @PostMapping("/getCompletions")
    public Set<Completion> getCompletions(@RequestBody CompletionRequest completionRequest)
        throws NoSuchMethodException {
        ParserContext context = ParserContext.noneContext();
        return codeParser.addCompetitionFromManagerOrClass(
            CodeParser.removeAllComments(completionRequest.getLine()),
            new Stack<>(),
            context,
            completionRequest.getAllScript());
    }

    @GetMapping(value = "/download/tmp/{fileName:.+}", produces = APPLICATION_OCTET_STREAM)
    public ResponseEntity<StreamingResponseBody> downloadFile(
        @PathVariable("FILE_NAME") String fileName) {
        Path outputPath = CommonUtils.getTmpPath().resolve(fileName);
        if (!Files.exists(outputPath)) {
            throw new NotFoundException("Unable to find file: " + fileName);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.add(
            HttpHeaders.CONTENT_DISPOSITION,
            String.format("attachment; filename=\"%s\"", outputPath.getFileName()));
        headers.add(HttpHeaders.CONTENT_TYPE, APPLICATION_OCTET_STREAM);

        return new ResponseEntity<>(
            outputStream -> {
                try (FileChannel inChannel = FileChannel.open(outputPath, StandardOpenOption.READ)) {
                    long size = inChannel.size();
                    WritableByteChannel writableByteChannel = Channels.newChannel(outputStream);
                    inChannel.transferTo(0, size, writableByteChannel);
                }
            },
            headers,
            HttpStatus.OK);
    }

    @PostMapping("/code/run")
    @RolesAllowed(ADMIN_ROLE)
    public RunScriptOnceJSON runScriptOnce(@RequestBody ScriptEntity scriptEntity)
        throws IOException {
        RunScriptOnceJSON runScriptOnceJSON = new RunScriptOnceJSON();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintStream logOutputStream = new PrintStream(outputStream);
        try {
            runScriptOnceJSON.result =
                scriptService.executeJavaScriptOnce(
                    scriptEntity,
                    scriptEntity.getJavaScriptParameters(),
                    logOutputStream,
                    false);
        } catch (Exception ex) {
            runScriptOnceJSON.error = ExceptionUtils.getStackTrace(ex);
        }
        int size = outputStream.size();
        if (size > 50000) {
            String name = scriptEntity.getEntityID() + "_size_" + outputStream.size() + "___.log";
            Path tempFile = CommonUtils.getTmpPath().resolve(name);
            Files.copy(tempFile, outputStream);
            runScriptOnceJSON.logUrl =
                "rest/download/tmp/" + CommonUtils.getTmpPath().relativize(tempFile);
        } else {
            runScriptOnceJSON.log = outputStream.toString(StandardCharsets.UTF_8);
        }

        return runScriptOnceJSON;
    }

    @GetMapping("/i18n/{lang}.json")
    @CacheControl(maxAge = 3600, policy = CachePolicy.PUBLIC)
    public ObjectNode getI18NFromBundles(@PathVariable("lang") String lang) {
        return Lang.getLangJson(lang);
    }

    @PostMapping("/notification/action")
    @RolesAllowed(ADMIN_ROLE)
    public ActionResponseModel notificationAction(@RequestBody HeaderActionRequest request) {
        try {
            return entityContext.ui().handleNotificationAction(request.entityID, request.actionEntityID, request.value);
        } catch (Exception ex) {
            throw new IllegalStateException(Lang.getServerMessage(ex.getMessage()));
        }
    }

    @SneakyThrows
    @PostMapping("/header/dialog/{entityID}")
    @RolesAllowed(ADMIN_ROLE)
    public void acceptDialog(@PathVariable("entityID") String entityID, @RequestBody DialogRequest dialogRequest) {
        entityContext.ui().handleDialog(entityID, EntityContextUI.DialogResponseType.Accepted, dialogRequest.pressedButton,
            OBJECT_MAPPER.readValue(dialogRequest.params, ObjectNode.class));
    }

    @DeleteMapping("/header/dialog/{entityID}")
    @RolesAllowed(ADMIN_ROLE)
    public void discardDialog(@PathVariable("entityID") String entityID) {
        entityContext.ui().handleDialog(entityID, EntityContextUI.DialogResponseType.Cancelled, null, null);
    }

    @Getter
    @Setter
    private static class DynamicRequestItem {

        private String eid;
        private String did;
    }

    @Getter
    @Setter
    private static class HeaderActionRequest {

        private String entityID;
        private String actionEntityID;
        private String value;
    }

    @Getter
    @Setter
    private static class DialogRequest {

        private String pressedButton;
        private String params;
    }

    @Getter
    @AllArgsConstructor
    private static class GitHubReadme {

        private String url;
        private String content;
    }

    @Getter
    private static class RunScriptOnceJSON {

        private Object result;
        private String log;
        private String error;
        private String logUrl;
    }

    @Getter
    @Setter
    private static class DeviceConfig {

        private final boolean bootOnly = false;
        private final boolean hasApp = true;
        private final boolean hasInitSetup = true;
    }

    @Getter
    @Setter
    private static class SourceHistoryRequest {

        private String dataSource;
        private JSONObject dynamicParameters;
        private int from;
        private int count;
    }

    @Getter
    @Setter
    private static class SourceHistoryChartRequest {

        private String dataSource;
        private JSONObject dynamicParameters;
        private int splitCount;
    }
}
