package com.depromeet.media.domain;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Music implements Serializable {
  public final String title;
  public final String artist;
  public final String genreName;
  public final String coverUrl;
  public final String musicUrl;
  public final Integer length;
}
