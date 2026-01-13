# force_delete lets destroy remove the repo with images still in it; otherwise destroy fails and storage keeps billing.
resource "aws_ecr_repository" "app" {
  name                 = "hotshield"
  image_tag_mutability = "MUTABLE"
  force_delete         = true
}
