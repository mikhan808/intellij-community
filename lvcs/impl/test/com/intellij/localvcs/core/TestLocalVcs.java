package com.intellij.localvcs.core;

import com.intellij.localvcs.core.storage.Content;
import com.intellij.localvcs.core.storage.Storage;

import java.io.IOException;

public class TestLocalVcs extends LocalVcs {
  private long myPurgingInterval;

  public TestLocalVcs() {
    this(new TestStorage());
  }

  public TestLocalVcs(Storage s) {
    super(s);
  }

  @Override
  protected Content createContentFrom(ContentFactory f) {
    try {
      if (f == null || f.getBytes() == null) return null;
      return f.createContent(myStorage);
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public long getPurgingPeriod() {
    return myPurgingInterval;
  }

  public void setPurgingPeriod(long i) {
    myPurgingInterval = i;
  }
}
