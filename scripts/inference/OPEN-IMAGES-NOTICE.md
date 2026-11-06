# Open Images attribution

These files are derived from the **Open Images Dataset V7** by Google LLC
(<https://storage.googleapis.com/openimages/web/index.html>):

- `label-eval-open-images-corpus.txt`, `label-eval-open-images-l5-corpus.txt` — image ids and
  hashes of a selection of its images;
- `label-eval-open-images-l5-selection.json`, `label-eval-open-images-mapping.tsv`,
  `label-eval-open-images-mapping-l5.tsv` — the selection and a mapping from its class labels;
- `runs/2026-10-15-l5-open-images/` — evaluation results over that selection.

The annotations (image-level labels and class names) are licensed by Google LLC under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). The images are listed as having a
[CC BY 2.0](https://creativecommons.org/licenses/by/2.0/) licence; **no image is stored in this
repository**, only ids and hashes. `label-eval.py` downloads the images from the dataset's own
source, and each one stays under its author's licence.

Changes: labels were selected and mapped onto Tenshu's label vocabulary, as described in
`label-eval.py`.
