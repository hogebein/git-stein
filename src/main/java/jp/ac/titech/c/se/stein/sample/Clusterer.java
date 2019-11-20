package jp.ac.titech.c.se.stein.sample;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import jp.ac.titech.c.se.stein.CLI;
import jp.ac.titech.c.se.stein.core.Config;
import jp.ac.titech.c.se.stein.core.Configurable;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.Graph;
import jp.ac.titech.c.se.stein.core.Graph.Vertex;
import jp.ac.titech.c.se.stein.core.RepositoryRewriter;
import jp.ac.titech.c.se.stein.core.Try;

public class Clusterer extends RepositoryRewriter implements Configurable {
    private static final Logger log = LoggerFactory.getLogger(Clusterer.class);

    private List<List<String>> clusters;

    protected final Map<ObjectId, ObjectId> mergeMapping = new HashMap<>();

    private final Graph graph = new Graph();

    @Override
    public void addOptions(final Config conf) {
        super.addOptions(conf);
        conf.addOption(null, "clusters", true, "set clustering info");
    }

    @Override
    public void configure(final Config conf) {
        super.configure(conf);
        if (conf.hasOption("clusters")) {
            clusters = loadJSON(conf.getOptionValue("clusters"));
        }
    }

    /**
     * Loads the clustering info.
     */
    protected List<List<String>> loadJSON(final String filename) {
        final Gson gson = new Gson();
        final TypeToken<List<List<String>>> t = new TypeToken<List<List<String>>>() {
        };
        try {
            return gson.fromJson(new FileReader(filename), t.getType());
        } catch (final JsonIOException | JsonSyntaxException | FileNotFoundException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    protected void rewriteCommits(final Context c) {
        graph.build(prepareRevisionWalk(c));
        log.debug("Graph: {} vertices, {} edges", graph.vertexSet().size(), graph.edgeSet().size());

        mergeClusters();

        try (final ObjectInserter ins = writeRepo.newObjectInserter()) {
            this.inserter = ins;
            graph.walk((id) -> rewriteCommit(Try.io(() -> repo.parseCommit(id)), c));
            this.inserter = null;
        }

        for (final Map.Entry<ObjectId, ObjectId> e : mergeMapping.entrySet()) {
            final ObjectId merged = e.getKey();
            final ObjectId base = e.getValue();
            final ObjectId rewritten = commitMapping.get(base);
            if (rewritten == null) {
                log.warn("Base commit has not rewritten yet: base: {}, merged: {} ({})", base.name(), merged.name(), c);
            } else {
                log.debug("Add commit mapping: {} merged into {} -> {} ({})", merged.name(), base.name(), rewritten.name(), c);
                commitMapping.put(merged, rewritten);
            }
        }
    }

    protected void mergeClusters() {
        int merged = 0;
        for (final List<String> cluster : clusters) {
            final Vertex base = Vertex.of(cluster.get(0));
            for (int i = 1; i < cluster.size(); i++) {
                final Vertex target = Vertex.of(cluster.get(i));
                if (mergeVertices(graph, base, target)) {
                    mergeMapping.put(target.id, base.id);
                    merged++;
                }
            }
        }
        log.debug("Merged {} commits in total", merged);
    }

    protected boolean mergeVertices(final Graph graph, final Vertex base, final Vertex target) {
        if (!graph.isMergeable(base, target)) {
            log.debug("Cycle detected, avoiding merging commits: {} <- {}", base, target);
            return false;
        }
        log.debug("Merge commits: {} <- {}", base, target);
        graph.mergeVertices(base, target);
        return true;
    }

    @Override
    protected ObjectId[] rewriteParents(final ObjectId[] parents, final Context c) {
        final ObjectId[] newParents = graph.getParentIds(c.getCommit().getId());
        if (log.isDebugEnabled()) {
            log.debug("Substitute parents: {} -> {} ({})",
                    Stream.of(parents).map(ObjectId::name),
                    Stream.of(newParents).map(ObjectId::name),
                    c);
        }
        return super.rewriteParents(newParents, c);
    }

    public static void main(final String[] args) {
        new CLI(Clusterer.class, args).run();
    }
}
