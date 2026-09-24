# 项目C/flake.nix
{
  description = "一个需要 JDK 25 的 Java 项目";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs =
    { self, nixpkgs }:
    let
      # 为你的系统架构定义开发环境
      system = "x86_64-linux"; # 或 "aarch64-linux", "x86_64-darwin" 等
      pkgs = nixpkgs.legacyPackages.${system};
    in
    {
      # 定义一个开发 Shell
      devShells.${system}.default = pkgs.mkShell {
        packages = [
          pkgs.temurin-bin-25
        ];
      };
    };
}
