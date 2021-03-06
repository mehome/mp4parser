/*
 * Copyright 2009 castLabs GmbH, Berlin
 *
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.coremedia.iso.boxes;

import com.coremedia.iso.boxes.fragment.TrackFragmentBox;

public class TrackMetaData<T> {
  private long trackId;
  private T trackBox;

  public TrackMetaData(long trackId, T trackBox) {
    this.trackId = trackId;
    this.trackBox = trackBox;
  }

  public long getTrackId() {
    return trackId;
  }

  public SampleDescriptionBox getSampleDescriptionBox() {
    if (trackBox instanceof TrackBox) {
      SampleTableBox sampleTableBox = ((TrackBox) trackBox).getBoxes(MediaBox.class)[0].
              getBoxes(MediaInformationBox.class)[0].
              getBoxes(SampleTableBox.class)[0];

      return sampleTableBox.getBoxes(SampleDescriptionBox.class)[0];
    } else if (trackBox instanceof TrackFragmentBox) {
      MovieBox[] movieBoxes = ((TrackFragmentBox) trackBox).getIsoFile().getBoxes(MovieBox.class);
      if (movieBoxes != null && movieBoxes.length > 0) {
        MovieBox movieBox = movieBoxes[0];
        TrackMetaData<TrackBox> moovTrackMetaData = movieBox.getTrackMetaData(((TrackFragmentBox) trackBox).getTrackFragmentHeaderBox().getTrackId());
        return moovTrackMetaData.getSampleDescriptionBox();
      } else {
        System.out.println("No movie box in file!");
        return null;
      }
    } else {
      System.out.println("Unsupported trackBox type " + trackBox);
      return null;
    }
  }
}
