// Project reference harness; the independently maintained upstream header retains its MIT notice.
#include "FastNoiseLite.h"
#include <bit>
#include <cstdint>
#include <iomanip>
#include <iostream>
#include <string>

int main() {
  const std::uint64_t seeds[]={0,1,UINT64_MAX,UINT64_C(0x123456789abcdef0)};
  const double locations[][3]={{0,0,0},{1.25,-2.5,3.75},{-31.5,7.125,32.5},{1024.25,-512.5,256.75},{-0.0,0.0,-0.0},{-17.75,-19.25,-23.5}};
  const char* keys[]={"OPEN_SIMPLEX_2","PERLIN","VALUE","VALUE_CUBIC","CELLULAR"};
  const FastNoiseLite::NoiseType types[]={FastNoiseLite::NoiseType_OpenSimplex2,FastNoiseLite::NoiseType_Perlin,FastNoiseLite::NoiseType_Value,FastNoiseLite::NoiseType_ValueCubic,FastNoiseLite::NoiseType_Cellular};
  std::cout<<"algorithm\tdimension\tseedHex\tx\ty\tz\tfrequency\tfractal\toctaves\tlacunarity\tgain\tpingPongStrength\tcellularDistance\tcellularReturn\tjitter\tvalueBits\n";
  auto sample=[&](int algorithm,int dimension,std::uint64_t seed,const double* p,double frequency,int fractal,int distance,int ret) {
    auto folded=static_cast<std::uint32_t>(seed^(seed>>32));
    FastNoiseLite field(std::bit_cast<std::int32_t>(folded));
    field.SetNoiseType(types[algorithm]);field.SetFrequency(static_cast<float>(frequency));
    field.SetFractalType(static_cast<FastNoiseLite::FractalType>(fractal));field.SetFractalOctaves(4);field.SetFractalLacunarity(2.25f);field.SetFractalGain(.375f);field.SetFractalPingPongStrength(2.5f);
    field.SetCellularDistanceFunction(static_cast<FastNoiseLite::CellularDistanceFunction>(distance));field.SetCellularReturnType(static_cast<FastNoiseLite::CellularReturnType>(ret));field.SetCellularJitter(.875f);
    double value=dimension==2 ? field.GetNoise<double>(p[0],p[2]) : field.GetNoise<double>(p[0],p[1],p[2]);
    std::cout<<keys[algorithm]<<'\t'<<dimension<<'\t'<<std::hex<<std::setw(16)<<std::setfill('0')<<seed<<std::dec<<std::setfill(' ')
      <<'\t'<<std::setprecision(17)<<p[0]<<'\t'<<p[1]<<'\t'<<p[2]<<'\t'<<frequency<<'\t'<<fractal<<"\t4\t2.25\t0.375\t2.5\t"<<distance<<'\t'<<ret<<"\t0.875\t"
      <<std::hex<<std::setw(16)<<std::setfill('0')<<std::bit_cast<std::uint64_t>(value)<<std::dec<<std::setfill(' ')<<'\n';
  };
  for(int a=0;a<5;a++)for(int d:{2,3})for(auto seed:seeds)for(auto& p:locations)for(double frequency:{.01,.125})for(int fractal=0;fractal<4;fractal++)sample(a,d,seed,p,frequency,fractal,1,1);
  for(int d:{2,3})for(auto seed:seeds)for(auto& p:locations)for(int distance=0;distance<4;distance++)for(int ret=0;ret<7;ret++)sample(4,d,seed,p,.125,0,distance,ret);
}
