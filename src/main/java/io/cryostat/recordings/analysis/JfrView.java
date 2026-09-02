/*
 * Copyright The Cryostat Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.cryostat.recordings.analysis;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import io.cryostat.recordings.RecordingHelper;

import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jdk.jfr.consumer.EventStream;
import jdk.jfr.internal.query.Configuration;
import jdk.jfr.internal.query.Configuration.Truncate;
import jdk.jfr.internal.query.ViewPrinter;
import jdk.jfr.internal.util.Output;
import jdk.jfr.internal.util.UserDataException;
import jdk.jfr.internal.util.UserSyntaxException;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;

/**
 * Expose the JDK {@code jfr view} command against archived recordings.
 *
 * <p>This reuses the JDK's own {@code jdk.jfr.internal.query.ViewPrinter} in-process, made
 * reachable via {@code --add-exports jdk.jfr/jdk.jfr.internal.query=ALL-UNNAMED} and {@code
 * --add-exports jdk.jfr/jdk.jfr.internal.util=ALL-UNNAMED}.
 */
@jakarta.ws.rs.Path("/api/beta/targets/{jvmId}/recordings/{filename}")
public class JfrView {

    @Inject AnalysisCache cache;
    @Inject RecordingHelper recordings;

    /**
     * Render a single {@code jfr view} against an archived recording and return it as plain text.
     *
     * @param jvmId the JVM ID (may be the synthetic {@code "uploads"}); together with {@code
     *     filename} this is the S3 key
     * @param filename the archived recording file name
     * @param view the view name or event type to render (default {@link #DEFAULT_VIEW})
     * @param width the view width in characters (default {@link #DEFAULT_WIDTH})
     * @param verbose whether to also display the query that makes up the view
     * @param truncate how to truncate content that exceeds a table cell: {@code "beginning"} or
     *     {@code "end"} (default depends on the view)
     * @param cellHeight the maximum number of rows in a table cell (default depends on the view)
     */
    @GET
    @jakarta.ws.rs.Path("view")
    @Blocking
    @RolesAllowed("read")
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<String> view(
            @RestPath String jvmId,
            @RestPath String filename,
            @RestQuery @DefaultValue(value = "recording") String view,
            @RestQuery @DefaultValue(value = "120") int width,
            @RestQuery @DefaultValue(value = "false") boolean verbose,
            @RestQuery String truncate,
            @RestQuery Optional<Integer> cellHeight)
            throws Exception {
        if (width <= 0) {
            throw new BadRequestException("width must be a positive integer");
        }
        if (cellHeight.isPresent() && cellHeight.get() <= 0) {
            throw new BadRequestException("cellHeight must be a positive integer");
        }

        Configuration config = new Configuration();
        config.width = width;
        config.verbose = verbose;
        parseTruncate(truncate).ifPresent(t -> config.truncate = t);
        cellHeight.ifPresent(h -> config.cellHeight = h);

        return Uni.createFrom()
                .completionStage(cache.get(jvmId, filename))
                .map(p -> renderView(p, view, config));
    }

    /**
     * Parse the {@code truncate} query param into the JDK {@code Truncate} mode, matching the
     * {@code jfr view --truncate} option. A {@code null} or blank value leaves the choice to the
     * view.
     */
    static Optional<Truncate> parseTruncate(String truncate) {
        if (truncate == null || truncate.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(
                switch (truncate.strip().toLowerCase(Locale.ROOT)) {
                    case "beginning" -> Truncate.BEGINNING;
                    case "end" -> Truncate.END;
                    default ->
                            throw new BadRequestException("truncate must be 'beginning' or 'end'");
                });
    }

    /**
     * List the views available to the {@code jfr view} command, grouped into JVM, environment, and
     * application categories. This is independent of any particular recording; the {@code jvmId}
     * and {@code filename} path segments are used only for existence validation and route
     * consistency.
     */
    @GET
    @jakarta.ws.rs.Path("views")
    @Blocking
    @RolesAllowed("read")
    @Produces(MediaType.APPLICATION_JSON)
    public ViewList views(@RestPath String jvmId, @RestPath String filename) {
        recordings.assertArchivedRecordingExists(jvmId, filename);
        return availableViews();
    }

    /**
     * In-process reuse of {@code jfr view}. The supplied {@code config} carries the view options
     * ({@code width}, {@code verbose}, {@code truncate}, {@code cellHeight}); its output stream is
     * wired up here.
     */
    String renderView(Path file, String view, Configuration config) {
        var baos = new ByteArrayOutputStream();
        try (var ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
                var stream = EventStream.openFile(file)) {
            Output.BufferedPrinter printer = new Output.BufferedPrinter(ps);
            config.output = printer;
            ViewPrinter vp = new ViewPrinter(config, stream);
            vp.execute(view);
            printer.flush();
        } catch (UserDataException | UserSyntaxException e) {
            throw new BadRequestException(e.getMessage(), e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    /**
     * Parse {@link ViewPrinter#getAvailableViews()} into categorized lists. That method returns the
     * same category-headed, column-wrapped text the {@code jfr help view} command prints; we split
     * it back into flat lists of view names keyed by category.
     */
    static ViewList availableViews() {
        List<String> vm = new ArrayList<>();
        List<String> env = new ArrayList<>();
        List<String> app = new ArrayList<>();
        List<String> current = null;
        for (String line : ViewPrinter.getAvailableViews()) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.endsWith("views:")) {
                if (trimmed.startsWith("Java virtual machine")) {
                    current = vm;
                } else if (trimmed.startsWith("Environment")) {
                    current = env;
                } else if (trimmed.startsWith("Application")) {
                    current = app;
                } else {
                    current = null;
                }
                continue;
            }
            if (current != null) {
                for (String token : trimmed.split("\\s+")) {
                    if (!token.isBlank()) {
                        current.add(token);
                    }
                }
            }
        }
        return new ViewList(vm, env, app);
    }

    public record ViewList(List<String> vm, List<String> env, List<String> app) {
        public ViewList {
            vm = Collections.unmodifiableList(new ArrayList<>(vm));
            env = Collections.unmodifiableList(new ArrayList<>(env));
            app = Collections.unmodifiableList(new ArrayList<>(app));
        }
    }
}
