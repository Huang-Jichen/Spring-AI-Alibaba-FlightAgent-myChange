package ai.spring.demo.ai.playground.Splitter;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class MyTextSplit extends TextSplitter {

    private static final int DEFAULT_CHUNK_SIZE = 800;
    private static final int MIN_CHUNK_SIZE_CHARS = 350;
    private static final int MIN_CHUNK_LENGTH_TO_EMBED = 5;
    private static final int MAX_NUM_CHUNKS = 10000;
    private static final boolean KEEP_SEPARATOR = true;
    private static final int DEFAULT_CHUNK_OVERLAP = 0;
    private final EncodingRegistry registry;
    private final Encoding encoding;
    private final int chunkSize;
    private final int chunkOverlap;
    private final int minChunkSizeChars;
    private final int minChunkLengthToEmbed;
    private final int maxNumChunks;
    private final boolean keepSeparator;
    private final List<String> splitList;

    public MyTextSplit() {
        this(DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP, MIN_CHUNK_SIZE_CHARS, MIN_CHUNK_LENGTH_TO_EMBED, MAX_NUM_CHUNKS, KEEP_SEPARATOR, Arrays.asList(".", "!", "?", "\n"));
    }

    public MyTextSplit(boolean keepSeparator) {
        this(DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP, MIN_CHUNK_SIZE_CHARS, MIN_CHUNK_LENGTH_TO_EMBED, MAX_NUM_CHUNKS, keepSeparator, Arrays.asList(".", "!", "?", "\n"));
    }

    public MyTextSplit(int chunkSize, int minChunkSizeChars, int minChunkLengthToEmbed, int maxNumChunks, boolean keepSeparator, List<String> splitList) {
        this(chunkSize, DEFAULT_CHUNK_OVERLAP, minChunkSizeChars, minChunkLengthToEmbed, maxNumChunks, keepSeparator, splitList);
    }

    public MyTextSplit(int chunkSize, int chunkOverlap, int minChunkSizeChars, int minChunkLengthToEmbed, int maxNumChunks, boolean keepSeparator, List<String> splitList) {
        Assert.isTrue(chunkSize > 0, "chunkSize must be greater than 0");
        Assert.isTrue(chunkOverlap >= 0, "chunkOverlap must be greater than or equal to 0");
        Assert.isTrue(chunkOverlap < chunkSize, "chunkOverlap must be smaller than chunkSize");
        this.registry = Encodings.newLazyEncodingRegistry();
        this.encoding = this.registry.getEncoding(EncodingType.CL100K_BASE);
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.minChunkSizeChars = minChunkSizeChars;
        this.minChunkLengthToEmbed = minChunkLengthToEmbed;
        this.maxNumChunks = maxNumChunks;
        this.keepSeparator = keepSeparator;
        if (splitList == null || splitList.isEmpty()) {
            this.splitList = Arrays.asList(".", "!", "?", "\n");
        }
        else {
            this.splitList = splitList;
        }
    }

    protected List<String> splitText(String text) {
        return this.doSplit(text, this.chunkSize);
    }

    protected List<String> doSplit(String text, int chunkSize) {
        if (text == null || text.trim().isEmpty()) {
            return new ArrayList<>();
        }

        List<Integer> allTokens = this.getEncodedTokens(text);
        List<String> chunks = new ArrayList<>();
        int start = 0;
        int numChunks = 0;

        while (start < allTokens.size() && numChunks < this.maxNumChunks) {
            int end = Math.min(start + chunkSize, allTokens.size());
            List<Integer> chunkTokens = safeSubList(allTokens, start, end, "chunk window");
            String chunkText = this.decodeTokens(chunkTokens);

            if (chunkText.trim().isEmpty()) {
                start = advanceStart(start, chunkTokens.size(), allTokens.size());
                numChunks++;
                continue;
            }

            int lastPunctuation = this.splitList.stream()
                    .mapToInt(chunkText::lastIndexOf)
                    .max()
                    .orElse(-1);
            if (lastPunctuation != -1 && lastPunctuation > this.minChunkSizeChars) {
                chunkText = chunkText.substring(0, lastPunctuation + 1);
            }

            String chunkTextToAppend = this.keepSeparator ? chunkText.trim() : chunkText.replace(System.lineSeparator(), " ").trim();
            if (chunkTextToAppend.length() > this.minChunkLengthToEmbed) {
                chunks.add(chunkTextToAppend);
            }

            int reEncodedTokens = this.getEncodedTokens(chunkText).size();
            int consumedTokens = Math.min(reEncodedTokens, chunkTokens.size());
            if (consumedTokens <= 0) {
                throw new IllegalStateException("Invalid split progress: consumedTokens=" + consumedTokens + ", reEncodedTokens=" + reEncodedTokens + ", start=" + start + ", end=" + end + ", chunkTextLength=" + chunkText.length());
            }
            if (reEncodedTokens > chunkTokens.size()) {
                consumedTokens = chunkTokens.size();
            }

            start = advanceStart(start, consumedTokens, allTokens.size());
            numChunks++;
        }

        return chunks;
    }

    private int advanceStart(int currentStart, int consumedTokens, int totalTokens) {
        int nextStart = currentStart + consumedTokens - this.chunkOverlap;
        if (nextStart <= currentStart) {
            nextStart = currentStart + 1;
        }
        return Math.min(nextStart, totalTokens);
    }

    private List<Integer> safeSubList(List<Integer> tokens, int fromIndex, int toIndex, String context) {
        Assert.notNull(tokens, "Tokens must not be null");
        if (fromIndex < 0 || toIndex < 0 || fromIndex > toIndex || toIndex > tokens.size()) {
            throw new IllegalArgumentException(
                    "Invalid subList range for " + context + ": fromIndex=" + fromIndex + ", toIndex=" + toIndex + ", size=" + tokens.size());
        }
        return tokens.subList(fromIndex, toIndex);
    }

    private List<Integer> getEncodedTokens(String text) {
        Assert.notNull(text, "Text must not be null");
        return this.encoding.encode(text).boxed();
    }

    private String decodeTokens(List<Integer> tokens) {
        Assert.notNull(tokens, "Tokens must not be null");
        IntArrayList tokensIntArray = new IntArrayList(tokens.size());
        Objects.requireNonNull(tokensIntArray);
        tokens.forEach(tokensIntArray::add);
        return this.encoding.decode(tokensIntArray);
    }

}
//原文链接：https://blog.csdn.net/weixin_43886636/article/details/149663489
