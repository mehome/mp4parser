package com.googlecode.mp4parser.authoring.builder;

import com.coremedia.iso.BoxParser;
import com.coremedia.iso.Hex;
import com.coremedia.iso.IsoFile;
import com.coremedia.iso.IsoTypeWriter;
import com.coremedia.iso.boxes.Box;
import com.coremedia.iso.boxes.CompositionTimeToSample;
import com.coremedia.iso.boxes.ContainerBox;
import com.coremedia.iso.boxes.DataEntryUrlBox;
import com.coremedia.iso.boxes.DataInformationBox;
import com.coremedia.iso.boxes.DataReferenceBox;
import com.coremedia.iso.boxes.FileTypeBox;
import com.coremedia.iso.boxes.HandlerBox;
import com.coremedia.iso.boxes.MediaBox;
import com.coremedia.iso.boxes.MediaHeaderBox;
import com.coremedia.iso.boxes.MediaInformationBox;
import com.coremedia.iso.boxes.MovieBox;
import com.coremedia.iso.boxes.MovieHeaderBox;
import com.coremedia.iso.boxes.SampleDependencyTypeBox;
import com.coremedia.iso.boxes.SampleTableBox;
import com.coremedia.iso.boxes.StaticChunkOffsetBox;
import com.coremedia.iso.boxes.TimeToSampleBox;
import com.coremedia.iso.boxes.TrackBox;
import com.coremedia.iso.boxes.TrackHeaderBox;
import com.coremedia.iso.boxes.fragment.MovieExtendsBox;
import com.coremedia.iso.boxes.fragment.MovieFragmentBox;
import com.coremedia.iso.boxes.fragment.MovieFragmentHeaderBox;
import com.coremedia.iso.boxes.fragment.SampleFlags;
import com.coremedia.iso.boxes.fragment.TrackExtendsBox;
import com.coremedia.iso.boxes.fragment.TrackFragmentBox;
import com.coremedia.iso.boxes.fragment.TrackFragmentHeaderBox;
import com.coremedia.iso.boxes.fragment.TrackRunBox;
import com.googlecode.mp4parser.authoring.DateHelper;
import com.googlecode.mp4parser.authoring.Movie;
import com.googlecode.mp4parser.authoring.Track;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.logging.Logger;

import static com.coremedia.iso.boxes.CastUtils.l2i;

/**
 * Creates a fragmented MP4 file.
 */
public class FragmentedMp4Builder implements Mp4Builder {
    FragmentIntersectionFinder intersectionFinder = new SyncSampleIntersectFinderImpl();

    private static final Logger LOG = Logger.getLogger(FragmentedMp4Builder.class.getName());

    public List<String> getAllowedHandlers() {
        return Arrays.asList("soun", "vide");
    }

    public FileTypeBox createFtyp(Movie movie) {
        List<String> minorBrands = new LinkedList<String>();
        minorBrands.add("isom");
        minorBrands.add("iso2");
        minorBrands.add("avc1");
        return new FileTypeBox("isom", 0, minorBrands);
    }

    public IsoFile build(Movie movie) throws IOException {
        LOG.info("Creating movie " + movie);
        IsoFile isoFile = new IsoFile();


        isoFile.addBox(createFtyp(movie));
        isoFile.addBox(createMovieBox(movie));


        int maxNumberOfFragments = 0;
        for (Track track : movie.getTracks()) {
            int currentLength = intersectionFinder.sampleNumbers(track, movie).length;
            maxNumberOfFragments = currentLength > maxNumberOfFragments ? currentLength : maxNumberOfFragments;
        }

        for (int i = 0; i < maxNumberOfFragments; i++) {
            for (Track track : movie.getTracks()) {
                if (getAllowedHandlers().isEmpty() || getAllowedHandlers().contains(track.getHandler())) {
                    int[] startSamples = intersectionFinder.sampleNumbers(track, movie);

                    if (i < startSamples.length) {
                        int startSample = startSamples[i];

                        int endSample = i + 1 < startSamples.length ? startSamples[i + 1] : track.getSamples().size();

                        if (startSample == endSample) {
                            // empty fragment
                            // just don't add any boxes.
                        } else {
                            isoFile.addBox(createMoof(startSample, endSample, track, i + 1));
                            isoFile.addBox(createMdat(startSample, endSample, track, i + 1));
                        }

                    } else {
                        //obvious this track has not that many fragments
                    }
                }
            }
        }


        return isoFile;
    }

    private Box createMdat(int startSample, int endSample, Track track, int i) {
        final List<ByteBuffer> samples = ByteBufferHelper.mergeAdjacentBuffers(track.getSamples().subList(startSample, endSample));
        return new Box() {
            ContainerBox parent;
            public ContainerBox getParent() {
                return parent;
            }

            public void setParent(ContainerBox parent) {
                this.parent = parent;
            }

            public long getSize() {
                long size = 8; // I don't expect 2gig fragments
                for (ByteBuffer sample : samples) {
                    size+=sample.limit();
                }
                return size;
            }

            public String getType() {
                return "mdat";
            }

            public void getBox(WritableByteChannel writableByteChannel) throws IOException {
                ByteBuffer header = ByteBuffer.allocate(8);
                IsoTypeWriter.writeUInt32(header, l2i(getSize()));
                header.put(IsoFile.fourCCtoBytes(getType()));
                header.rewind();
                writableByteChannel.write(header);
                if (writableByteChannel instanceof GatheringByteChannel) {

                    int STEPSIZE = 1024;
                    for (int i = 0; i < Math.ceil((double) samples.size() / STEPSIZE); i++) {
                        List<ByteBuffer> sublist = samples.subList(
                                i * STEPSIZE, // start
                                (i + 1) * STEPSIZE < samples.size() ? (i + 1) * STEPSIZE : samples.size()); // end
                        ByteBuffer sampleArray[] = sublist.toArray(new ByteBuffer[sublist.size()]);
                        do {
                            ((GatheringByteChannel) writableByteChannel).write(sampleArray);
                        } while (sampleArray[sampleArray.length - 1].remaining() > 0);
                    }
                    //System.err.println(bytesWritten);
                } else {
                    for (ByteBuffer sample : samples) {
                        sample.rewind();
                        writableByteChannel.write(sample);
                    }
                }

            }

            public void parse(ReadableByteChannel inFC, ByteBuffer header, long contentSize, BoxParser boxParser) throws IOException {

            }
        };

    }

    public static void dumpHex(ByteBuffer bb) {
        byte[] b = new byte[bb.limit()];
        bb.get(b);
        System.err.println(Hex.encodeHex(b));
        bb.rewind();

    }

    Box createTfhd(int startSample, int endSample, Track track, int sequenceNumber) {
        TrackFragmentHeaderBox tfhd = new TrackFragmentHeaderBox();
        SampleFlags sf = new SampleFlags();

        tfhd.setDefaultSampleFlags(sf);
        tfhd.setBaseDataOffset(-1);
        tfhd.setTrackId(track.getTrackMetaData().getTrackId());
        return tfhd;
    }

    Box createMfhd(int startSample, int endSample, Track track, int sequenceNumber) {
        MovieFragmentHeaderBox mfhd = new MovieFragmentHeaderBox();
        mfhd.setSequenceNumber(sequenceNumber);
        return mfhd;
    }

    Box createTraf(int startSample, int endSample, Track track, int sequenceNumber) {
        TrackFragmentBox traf = new TrackFragmentBox();
        traf.addBox(createTfhd(startSample, endSample, track, sequenceNumber));
        for (Box trun : createTruns(startSample, endSample, track, sequenceNumber)) {
            traf.addBox(trun);
        }

        return traf;
    }



    List<? extends Box> createTruns(int startSample, int endSample, Track track, int sequenceNumber) {
        List<ByteBuffer> samples = track.getSamples().subList(startSample, endSample);

        long[] sampleSizes = new long[samples.size()];
        for (int i = 0; i < sampleSizes.length; i++) {
            sampleSizes[i] = samples.get(i).limit();
        }
        TrackRunBox trun = new TrackRunBox();


        trun.setSampleDurationPresent(true);
        trun.setSampleSizePresent(true);
        List<TrackRunBox.Entry> entries = new ArrayList<TrackRunBox.Entry>(endSample - startSample);


        Queue<TimeToSampleBox.Entry> timeQueue = new LinkedList<TimeToSampleBox.Entry>(track.getDecodingTimeEntries());
        long durationEntriesLeft = timeQueue.peek().getCount();


        Queue<CompositionTimeToSample.Entry> compositionTimeQueue =
                track.getCompositionTimeEntries() != null && track.getCompositionTimeEntries().size() > 0 ?
                        new LinkedList<CompositionTimeToSample.Entry>(track.getCompositionTimeEntries()) : null;
        long compositionTimeEntriesLeft = compositionTimeQueue != null ? compositionTimeQueue.peek().getCount() : -1;


        boolean sampleFlagsRequired = (track.getSampleDependencies() != null && !track.getSampleDependencies().isEmpty() ||
                track.getSyncSamples() != null && track.getSyncSamples().length != 0);

        trun.setSampleFlagsPresent(sampleFlagsRequired);

        for (int i = 0; i < sampleSizes.length; i++) {
            TrackRunBox.Entry entry = new TrackRunBox.Entry();
            entry.setSampleSize(sampleSizes[i]);
            if (sampleFlagsRequired) {
                //if (false) {
                SampleFlags sflags = new SampleFlags();

                if (track.getSampleDependencies() != null && !track.getSampleDependencies().isEmpty()) {
                    SampleDependencyTypeBox.Entry e = track.getSampleDependencies().get(i);
                    sflags.setSampleDependsOn(e.getSampleDependsOn());
                    sflags.setSampleIsDependedOn(e.getSampleIsDependentOn());
                    sflags.setSampleHasRedundancy(e.getSampleHasRedundancy());
                }
                if (track.getSyncSamples() != null && track.getSyncSamples().length > 0) {
                    // we have to mark non-sync samples!
                    sflags.setSampleIsDifferenceSample(Arrays.binarySearch(track.getSyncSamples(), startSample + i + 1) < 0);
                }
                // i don't have sample degradation
                entry.setSampleFlags(sflags);

            }

            entry.setSampleDuration(timeQueue.peek().getDelta());
            if (--durationEntriesLeft == 0 && timeQueue.size() > 1) {
                timeQueue.remove();
                durationEntriesLeft = timeQueue.peek().getCount();
            }

            if (compositionTimeQueue != null) {
                trun.setSampleCompositionTimeOffsetPresent(true);
                entry.setSampleCompositionTimeOffset(compositionTimeQueue.peek().getOffset());
                if (--compositionTimeEntriesLeft == 0 && compositionTimeQueue.size() > 1) {
                    compositionTimeQueue.remove();
                    compositionTimeEntriesLeft = compositionTimeQueue.element().getCount();
                }
            }
            entries.add(entry);
        }

        trun.setEntries(entries);

        return Collections.singletonList(trun);
    }

    Box createMoof(int startSample, int endSample, Track track, int sequenceNumber) {


        MovieFragmentBox moof = new MovieFragmentBox();
        moof.addBox(createMfhd(startSample, endSample, track, sequenceNumber));
        moof.addBox(createTraf(startSample, endSample, track, sequenceNumber));

        TrackRunBox firstTrun = moof.getTrackRunBoxes().get(0);
        firstTrun.setDataOffset(1); // dummy to make size correct
        firstTrun.setDataOffset((int) (8 + moof.getSize())); // mdat header + moof size

        return moof;
    }

    Box createMvhd(Movie movie) {
        MovieHeaderBox mvhd = new MovieHeaderBox();
        mvhd.setCreationTime(DateHelper.convert(new Date()));
        mvhd.setModificationTime(DateHelper.convert(new Date()));
        long movieTimeScale = movie.getTimescale();
        long duration = 0;

        for (Track track : movie.getTracks()) {
            long tracksDuration = getDuration(track) * movieTimeScale / track.getTrackMetaData().getTimescale();
            if (tracksDuration > duration) {
                duration = tracksDuration;
            }


        }

        mvhd.setDuration(duration);
        mvhd.setTimescale(movieTimeScale);
        // find the next available trackId
        long nextTrackId = 0;
        for (Track track : movie.getTracks()) {
            nextTrackId = nextTrackId < track.getTrackMetaData().getTrackId() ? track.getTrackMetaData().getTrackId() : nextTrackId;
        }
        mvhd.setNextTrackId(++nextTrackId);
        return mvhd;
    }

    Box createMovieBox(Movie movie) {
        MovieBox movieBox = new MovieBox();

        movieBox.addBox(createMvhd(movie));
        movieBox.addBox(createMvex(movie));

        for (Track track : movie.getTracks()) {
            movieBox.addBox(createTrak(track, movie));
        }
        // metadata here
        return movieBox;

    }

    Box createTrex(Movie movie, Track track) {
        TrackExtendsBox trex = new TrackExtendsBox();
        trex.setTrackId(track.getTrackMetaData().getTrackId());
        trex.setDefaultSampleDescriptionIndex(1);
        trex.setDefaultSampleDuration(0);
        trex.setDefaultSampleSize(0);
        trex.setDefaultSampleFlags(new SampleFlags());
        return trex;
    }


    Box createMvex(Movie movie) {
        MovieExtendsBox mvex = new MovieExtendsBox();

        for (Track track : movie.getTracks()) {
            mvex.addBox(createTrex(movie, track));
        }
        return mvex;
    }

    Box createTkhd(Movie movie, Track track) {
        TrackHeaderBox tkhd = new TrackHeaderBox();
        int flags = 0;
        if (track.isEnabled()) {
            flags += 1;
        }

        if (track.isInMovie()) {
            flags += 2;
        }

        if (track.isInPreview()) {
            flags += 4;
        }

        if (track.isInPoster()) {
            flags += 8;
        }
        tkhd.setFlags(flags);

        tkhd.setAlternateGroup(track.getTrackMetaData().getGroup());
        tkhd.setCreationTime(DateHelper.convert(track.getTrackMetaData().getCreationTime()));
        // We need to take edit list box into account in trackheader duration
        // but as long as I don't support edit list boxes it is sufficient to
        // just translate media duration to movie timescale
        tkhd.setDuration(getDuration(track) * movie.getTimescale() / track.getTrackMetaData().getTimescale());
        tkhd.setHeight(track.getTrackMetaData().getHeight());
        tkhd.setWidth(track.getTrackMetaData().getWidth());
        tkhd.setLayer(track.getTrackMetaData().getLayer());
        tkhd.setModificationTime(DateHelper.convert(new Date()));
        tkhd.setTrackId(track.getTrackMetaData().getTrackId());
        tkhd.setVolume(track.getTrackMetaData().getVolume());
        return tkhd;
    }

    Box createMdhd(Movie movie, Track track) {
        MediaHeaderBox mdhd = new MediaHeaderBox();
        mdhd.setCreationTime(DateHelper.convert(track.getTrackMetaData().getCreationTime()));
        mdhd.setDuration(getDuration(track));
        mdhd.setTimescale(track.getTrackMetaData().getTimescale());
        mdhd.setLanguage(track.getTrackMetaData().getLanguage());
        return mdhd;
    }

    Box createStbl(Movie movie, Track track) {
        SampleTableBox stbl = new SampleTableBox();

        stbl.addBox(track.getSampleDescriptionBox());
        stbl.addBox(new TimeToSampleBox());
        //stbl.addBox(new SampleToChunkBox());
        stbl.addBox(new StaticChunkOffsetBox());
        return stbl;
    }

    Box createMinf(Track track, Movie movie) {
        MediaInformationBox minf = new MediaInformationBox();
        minf.addBox(track.getMediaHeaderBox());
        minf.addBox(createDinf(movie, track));
        minf.addBox(createStbl(movie, track));
        return minf;
    }

    Box createMdia(Track track, Movie movie) {
        MediaBox mdia = new MediaBox();
        mdia.addBox(createMdhd(movie, track));

        HandlerBox hdlr = new HandlerBox();
        mdia.addBox(hdlr);
        hdlr.setHandlerType(track.getHandler());

        mdia.addBox(createMinf(track, movie));
        return mdia;
    }

    Box createTrak(Track track, Movie movie) {
        LOG.info("Creating Track " + track);
        TrackBox trackBox = new TrackBox();
        trackBox.addBox(createTkhd(movie, track));
        trackBox.addBox(createMdia(track, movie));
        return trackBox;
    }

    private DataInformationBox createDinf(Movie movie, Track track) {
        DataInformationBox dinf = new DataInformationBox();
        DataReferenceBox dref = new DataReferenceBox();
        dinf.addBox(dref);
        DataEntryUrlBox url = new DataEntryUrlBox();
        url.setFlags(1);
        dref.addBox(url);
        return dinf;
    }

    public void setIntersectionFinder(FragmentIntersectionFinder intersectionFinder) {
        this.intersectionFinder = intersectionFinder;
    }

    protected long getDuration(Track track) {
        long duration = 0;
        for (TimeToSampleBox.Entry entry : track.getDecodingTimeEntries()) {
            duration += entry.getCount() * entry.getDelta();
        }
        return duration;
    }


}
