"""Compare deployment runtimes on actual server hardware, without writing images."""
import argparse
import json
import statistics
import time
from pathlib import Path

from PIL import Image, ImageOps
from ultralytics import YOLO
import numpy as np
import torch


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("models", nargs="+")
    parser.add_argument("--images", required=True, help="Folder of representative photos, never the whole training set")
    parser.add_argument("--threads", type=int, default=2)
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--repeat", type=int, default=3)
    args = parser.parse_args()
    torch.set_num_threads(args.threads)
    paths = sorted(p for p in Path(args.images).iterdir() if p.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp"})
    if not paths or args.repeat < 1:
        parser.error("Provide at least one photo and a positive repeat count")
    images = []
    for path in paths:
        with Image.open(path) as source:
            image = ImageOps.exif_transpose(source).convert("RGB")
            image.thumbnail((2048, 2048))
            images.append(np.asarray(image)[:, :, ::-1].copy())
    for model_path in args.models:
        model = YOLO(model_path, task="detect")
        predict = lambda image: model.predict(image, imgsz=args.imgsz, device="cpu", conf=.35, iou=.45, verbose=False)
        predict(images[0])
        durations = []
        summary = []
        for repetition in range(args.repeat):
            for path, image in zip(paths, images):
                start = time.perf_counter()
                result = predict(image)[0]
                durations.append(time.perf_counter() - start)
                if repetition == 0:
                    summary.append({"photo": path.name, "labels": [result.names[int(c)] for c in result.boxes.cls.tolist()],
                                    "scores": result.boxes.conf.tolist(), "boxes": result.boxes.xyxyn.tolist()})
        print(json.dumps({"model":Path(model_path).name,"count":len(durations),"median_seconds":statistics.median(durations),
                          "p95_seconds":sorted(durations)[max(0, int(.95*len(durations))-1)],"results":summary},ensure_ascii=False))


if __name__ == "__main__":
    main()
