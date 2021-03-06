import React from "react";
import "./Loading.css"

const Loading = (props) => {

  return (<main>
    <svg viewBox="-50 -50 100 100" width="100" height="100">
    <defs>
      <path id="line" d="M -50 0 h 100" />
      <path id="slice" d="M 0 0 v -50 h 50 z" />
      <mask id="mask-circle">
        <circle r="48" stroke-width="4" fill="none" stroke="hsl(0, 0%, 100%)" />
        <g fill="none" stroke="hsl(0, 0%, 0%)" stroke-width="6">
          <use href="#line" />
          <use transform="rotate(45)" href="#line" />
          <use transform="rotate(-45)" href="#line" />
          <use transform="rotate(90)" href="#line" />
        </g>
      </mask>
    </defs>
    <g mask="url(#mask-circle)">
      <g class="reduce-opacity">
        <g fill="currentColor">
          <use href="#slice" />
          <use transform="rotate(45)" href="#slice" />
          <use transform="rotate(90)" href="#slice" />
          <use transform="rotate(135)" href="#slice" />
          <use transform="rotate(180)" href="#slice" />
          <use transform="rotate(225)" href="#slice" />
          <use transform="rotate(270)" href="#slice" />
          <use transform="rotate(315)" href="#slice" />
        </g>
      </g>
    </g>
    <g class="transform-rotateY">
      <g fill="none" stroke="currentColor" stroke-width="8" stroke-linecap="square">
        <path d="M -20 0 l 20 -22 20 22 -20 22" />
      </g>
    </g>
  </svg>

  <div className="LoadingMsg">
    <p>we prepare Your results Now...</p>
    <p>Please be patient.</p>
  </div>
  </main>
  );
};

export default Loading;