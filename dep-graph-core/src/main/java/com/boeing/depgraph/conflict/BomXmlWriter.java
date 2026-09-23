package com.boeing.depgraph.conflict;

import com.boeing.depgraph.model.BomProposal;
import com.boeing.depgraph.model.ConflictSeverity;

import java.util.List;

/**
 * Renders a {@link BomProposal} as the {@code <dependencyManagement>} block it describes.
 *
 * <p>A proposal nobody can paste into a pom is a report, not a proposal. This is the
 * smallest thing that closes that gap, and it deliberately stops short of editing files:
 * rewriting poms in place is the job of the OpenRewrite step, and the local cautionary
 * tale - a tool in this estate that writes in place with no dry-run and is not idempotent
 * - is the reason the printing and the writing are separate pieces of work.
 *
 * <p>Every entry carries its rationale as an XML comment. An unexplained managed version
 * is indistinguishable from one nobody has revisited, which is the condition this whole
 * exercise exists to escape.
 */
public final class BomXmlWriter {

    private BomXmlWriter() {
    }

    public static String toXml(BomProposal proposal) {
        StringBuilder xml = new StringBuilder();
        xml.append("<dependencyManagement>\n    <dependencies>\n");

        if (proposal.isEmpty()) {
            xml.append("        <!-- Nothing to manage: every version is already decided "
                + "in exactly one place. -->\n");
        }

        // Review-worthy entries first. A reviewer who reads only the top of the block
        // should have read the rows that need a decision.
        List<BomProposal.Entry> ordered = proposal.entries().stream()
            .sorted((a, b) -> Integer.compare(b.severity().weight(), a.severity().weight()))
            .toList();

        ConflictSeverity current = null;
        for (BomProposal.Entry entry : ordered) {
            if (entry.severity() != current) {
                current = entry.severity();
                xml.append("\n        <!-- ").append(comment(current.displayName().toUpperCase()))
                   .append(" -->\n");
            }
            xml.append("        <!-- ").append(comment(entry.rationale())).append(" -->\n");
            xml.append("        <dependency>\n")
               .append("            <groupId>").append(escape(entry.key().groupId())).append("</groupId>\n")
               .append("            <artifactId>").append(escape(entry.key().artifactId())).append("</artifactId>\n")
               .append("            <version>").append(escape(entry.version())).append("</version>\n")
               .append("        </dependency>\n");
        }

        xml.append("\n    </dependencies>\n</dependencyManagement>\n");

        if (!proposal.excluded().isEmpty()) {
            // Outside the block, because these are the ones it does not fix. Burying them
            // inside as comments would let someone paste the block and believe they were
            // done.
            xml.append("\n<!-- NOT MANAGED HERE - ").append(proposal.excluded().size())
               .append(" artifact(s) this block deliberately leaves alone:\n");
            for (BomProposal.Excluded excluded : proposal.excluded()) {
                xml.append("     ").append(excluded.key()).append(" - ")
                   .append(comment(excluded.reason())).append('\n');
            }
            xml.append("-->\n");
        }
        return xml.toString();
    }

    /**
     * Element text: the three characters that cannot appear raw.
     *
     * <p>Only ever applied to a groupId, artifactId or version, none of which should
     * contain any of them - but a BOM this tool prints may be pasted straight into a
     * build, and "should not" is not a reason to emit XML that might not parse.
     */
    private static String escape(String value) {
        return value == null ? "" : value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    /**
     * Comment text: only {@code --} is illegal inside an XML comment.
     *
     * <p>Angle brackets and ampersands are fine there, and escaping them turns a
     * rationale that reads as a sentence - "patch-level skew only (32.1.2 -> 32.1.3)" -
     * into one that reads as markup. The comments exist to be read by whoever reviews
     * the block, so legibility is the requirement.
     */
    private static String comment(String value) {
        return value == null ? "" : value.replace("--", "-");
    }
}
